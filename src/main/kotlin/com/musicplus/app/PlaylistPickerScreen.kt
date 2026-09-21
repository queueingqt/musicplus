package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.PlaylistRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlaylistPickerScreenViewModel(
    private val playlistRepository: PlaylistRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val listRefresher: ListRefresher,
) : LightViewModel<Unit>() {

    // The page opens straight from the cache; this re-checks the server in the
    // background and any additions or deletions arrive through the cache
    // itself — no spinner. See ListRefresher's doc.
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        listRefresher.refreshOnOpen(ListRefresher.Target.PLAYLISTS)
    }


    // See AppLibraryCache's doc — reads the already-live, process-lifetime
    // cache instead of re-subscribing to playlistRepository.observePlaylists()
    // on every fresh per-visit ViewModel.
    val playlists: StateFlow<List<Playlist>> = AppLibraryCache.playlists.value

    suspend fun addToExisting(playlistId: String, songId: String) {
        syncQueueRepository.addTrack(playlistId, songId)
    }

    /** Creates the playlist (real, or a local placeholder if it's queued — see SyncQueueRepository.createPlaylist), adds [songId], then calls [onDone] on the main thread. */
    fun createAndAdd(name: String, songId: String, onDone: () -> Unit) {
        // viewModelScope, not the screen's rememberCoroutineScope(): this runs from the name editor's result callback, and
        // that scope is cancelled while the editor is on top of this screen, so the create used to silently never run.
        viewModelScope.launch {
            val id = syncQueueRepository.createPlaylist(name)
            if (id != null) syncQueueRepository.addTrack(id, songId)
            onDone()
            playlistRepository.refreshPlaylists()
        }
    }
}

/**
 * Minimal "add to playlist" picker for track rows in AlbumDetailScreen/
 * SearchScreen/FavoritesScreen (issue #5, item 5). Documented choice: a small
 * dedicated screen rather than reusing PlaylistListScreen in a "pick mode" —
 * PlaylistListScreen already owns its own forward-navigation (into
 * PlaylistDetailScreen), search-filter state, and "new playlist" flow; threading a
 * second pick-mode behavior through all of that for every row's onClick would cost
 * more than this file, which is a simple flat list + one "new playlist" row.
 * Tapping a row (existing or new) adds the track immediately and navigates back —
 * no further confirmation UI, matching the rest of this feature's "make it work,
 * keep it small" first pass.
 */
class PlaylistPickerScreen(
    activity: SealedLightActivity,
    private val songId: String,
) : LightScreen<Unit, PlaylistPickerScreenViewModel>(activity) {

    override val viewModelClass = PlaylistPickerScreenViewModel::class.java

    override fun createViewModel(): PlaylistPickerScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return PlaylistPickerScreenViewModel(graph.playlistRepository, graph.syncQueueRepository, graph.listRefresher)
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val playlists by viewModel.playlists.collectAsState()

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Add to playlist"),
                )
            },
        ) {
            LightText(
                text = "New playlist",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        navigateTo({ a -> TextEditScreen(a, "Playlist name", "") }) { name ->
                            if (!name.isNullOrBlank()) viewModel.createAndAdd(name, songId) { goBack() }
                        }
                    }
                    .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
            )

            // Inside, not the default Outside — see ScrollbarGutter.kt's doc
            // (issue #39): a wider available width on the first frame (before
            // the scrollbar's real gutter is reserved) can change how/whether
            // a long playlist name wraps, then visibly reflow once it's
            // reserved a frame later.
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                uniformItemHeightGridUnits = 3f,
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    LightText(
                        text = playlist.name,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                scope.launch {
                                    viewModel.addToExisting(playlist.id, songId)
                                    goBack()
                                }
                            }
                            // end matches the SDK's own scrollbar track width —
                            // see the LightLazyScrollView call site above for why
                            // this is fixed rather than conditional on whether a
                            // scrollbar happens to show.
                            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
