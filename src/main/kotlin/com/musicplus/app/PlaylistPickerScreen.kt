package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.PlaylistHomes
import com.musicplus.app.data.PlaylistRepository
import com.musicplus.app.data.ServerLabels
import com.musicplus.app.data.ServerScope
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Everything here that outlives a tap runs on [viewModelScope], and reports back through a callback that
 * runs on the main thread. The screen's own `rememberCoroutineScope()` is cancelled whenever another screen (the name
 * editor, say) is on top of this one, so a create launched from the editor's result callback on that scope never ran:
 * "New playlist" silently did nothing.
 */
class PlaylistPickerScreenViewModel(
    private val playlistRepository: PlaylistRepository,
    private val syncQueueRepository: SyncQueueRepository,
    listRefresher: ListRefresher,
    private val songId: String,
) : CachedListViewModel(listRefresher, ListRefresher.Target.PLAYLISTS) {

    /**
     * Every playlist, each with its home shown by its row. The ones that take this song as they are come first, so the common
     * choice needs no warning; the rest follow, in name order within each group.
     */
    val playlists: StateFlow<List<Playlist>> =
        combine(AppLibraryCache.playlists.value, AppServerPrefs.capabilities.value, AppServerPrefs.enabledServerIds.value) { all, _, _ ->
            val (takesAsIs, needsCopy) = all.partition { PlaylistHomes.takesAsIs(it.id, songId) }
            takesAsIs + needsCopy
        }.screenState(viewModelScope, emptyList())

    fun addToExisting(playlistId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            syncQueueRepository.addTrack(playlistId, songId)
            onDone()
        }
    }

    /** Adds the song to a new Phone Only copy of [playlistId]; [onDone] gets the copy's id, or null if the original could not be read in full. */
    fun addToPhoneCopy(playlistId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(syncQueueRepository.addToPhoneCopy(playlistId, songId)) }
    }

    /** A new playlist made from this song goes to the song's own server, or the phone when that server cannot keep playlists. */
    fun createAndAdd(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val home = PlaylistHomes.homeOfSong(songId)
            val id = syncQueueRepository.createPlaylist(name, home)
            if (id != null) syncQueueRepository.addTrack(id, songId)
            onDone()
            if (home != ServerScope.PHONE) playlistRepository.refreshPlaylists()
        }
    }
}

class PlaylistPickerScreen(
    activity: SealedLightActivity,
    private val songId: String,
) : LightScreen<Unit, PlaylistPickerScreenViewModel>(activity) {

    override val viewModelClass = PlaylistPickerScreenViewModel::class.java

    override fun createViewModel(): PlaylistPickerScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return PlaylistPickerScreenViewModel(graph.playlistRepository, graph.syncQueueRepository, graph.listRefresher, songId)
    }

    /**
     * Adding this song would make [playlist] differ from the one on its server, so it goes to a new Phone Only copy instead,
     * and this asks first, every time. The original is not touched.
     */
    private fun confirmCopy(playlist: Playlist) {
        val server = ServerLabels.nameOf(ServerScope.serverOf(playlist.id)) ?: "its server"
        LightModalManager.show(
            ConfirmModal(
                title = "Add to a Phone Only copy?",
                message = "${PlaylistHomes.whyCopy(playlist.id, songId)} Adding it makes a Phone Only copy of \"${playlist.name}\". The original stays on $server.",
                confirmContentDescription = "Add",
                onConfirm = {
                    viewModel.addToPhoneCopy(playlist.id) { copyId ->
                        if (copyId == null) NoteModal.show("Couldn't copy \"${playlist.name}\"")
                        goBack()
                    }
                },
            ),
            duration = 30.seconds,
        )
    }

    @Composable
    override fun Content() {
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
                            if (!name.isNullOrBlank()) viewModel.createAndAdd(name.trim()) { goBack() }
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                if (PlaylistHomes.takesAsIs(playlist.id, songId)) {
                                    viewModel.addToExisting(playlist.id) { goBack() }
                                } else {
                                    confirmCopy(playlist)
                                }
                            }
                            // end matches the SDK's own scrollbar track width —
                            // see the LightLazyScrollView call site above for why
                            // this is fixed rather than conditional on whether a
                            // scrollbar happens to show.
                            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
                    ) {
                        LightText(text = playlist.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LightText(text = playlist.detailLine, variant = LightTextVariant.Fine)
                    }
                }
            }
        }
    }
}
