package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.playbackRepository
import com.musicplus.app.data.PlaylistRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistDetailScreenViewModel(
    playlistRepository: PlaylistRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val playlistId: String,
    startInReorderMode: Boolean = false,
) : ListScreenViewModel() {

    // See SelfLoadingTrackList.kt: the tracks are read through the refresh-then-read guarantee it exists to enforce.
    private val selfLoadingTracks = SelfLoadingTrackList.forPlaylist(playlistRepository, playlistId)

    val tracks: StateFlow<List<Track>> = selfLoadingTracks.observeTracks().screenState(viewModelScope, emptyList())

    val playlist: StateFlow<Playlist?> = playlistRepository.observePlaylist(playlistId).screenState(viewModelScope, null)

    // A plain `remember` inside Content() doesn't survive this screen's own
    // navigate-away/goBack() round trip when "Edit order" pushes ActionsMenuScreen
    // on top and pops back — Content() is fully disposed while hidden and
    // recomposed from scratch when shown again (see ScrollPosition.kt's doc for
    // the fuller story; same root cause). Reported live: toggling reorder mode
    // from the action menu had no visible effect at all, because the mutation
    // landed on a `remember`'d state object whose composition had already been
    // torn down by the time it ran. Lives on the ViewModel instead, which
    // (unlike Content()) survives that round trip.
    private val _reorderMode = MutableStateFlow(startInReorderMode)
    val reorderMode: StateFlow<Boolean> = _reorderMode.asStateFlow()
    fun setReorderMode(enabled: Boolean) { _reorderMode.value = enabled }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { selfLoadingTracks.refreshNow() }
    }

    /** [position] is the track's zero-based index in the currently-displayed (i.e. server) order. Suspend, so the menu row that triggers it can drop itself once it is done. */
    suspend fun removeTrack(position: Int) {
        syncQueueRepository.removeTrack(playlistId, position)
    }

    fun moveUp(position: Int) {
        viewModelScope.launch { syncQueueRepository.moveTrackUp(playlistId, position) }
    }

    fun moveDown(position: Int) {
        viewModelScope.launch { syncQueueRepository.moveTrackDown(playlistId, position) }
    }
}

/**
 * `activity` is retained as a property for the same reason as AlbumDetailScreen's —
 * per-track play needs it for `playbackRepository(...)` from `Content()`.
 */
class PlaylistDetailScreen(
    private val activity: SealedLightActivity,
    private val playlistId: String,
    // Lets the Playlists list screen's own "Edit order" menu item land here
    // with reorder mode already active, instead of navigating in and making
    // the person long-press the title a second time.
    private val startInReorderMode: Boolean = false,
) : LightScreen<Unit, PlaylistDetailScreenViewModel>(activity) {

    override val viewModelClass = PlaylistDetailScreenViewModel::class.java

    override fun createViewModel(): PlaylistDetailScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return PlaylistDetailScreenViewModel(graph.playlistRepository, graph.syncQueueRepository, playlistId, startInReorderMode)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val playlist by viewModel.playlist.collectAsState()
        val title = playlist?.name ?: "Playlist"
        val trackActions = rememberTrackActions(activity, lightContext)
        val playlistActions = rememberPlaylistActions(activity, lightContext, viewModel.viewModelScope)

        // Reorder handles are opt-in, entered via the title's long-press menu
        // ("Edit order") rather than always visible — reported live: previously
        // shown for every track whether or not the person was actually
        // reordering anything. Exited via the top bar's Done button while active.
        // Lives on the ViewModel, not a local `remember` — see its own doc for why.
        val reorderMode by viewModel.reorderMode.collectAsState()

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    // Same BACK glyph either way — only what it does changes. While
                    // reordering it exits the mode (stays on this same screen)
                    // instead of leaving the screen entirely; each move is already
                    // applied immediately (moveUp/moveDown persist right away), so
                    // there's nothing to confirm on the way out. Reported live.
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        contentDescription = if (reorderMode) "Done reordering" else "Back",
                        onClick = {
                            if (reorderMode) viewModel.setReorderMode(false) else goBack()
                        },
                    ),
                    center = LightTopBarCenter.Text("Playlist"),
                )
            },
        ) {
            // The trash icon that used to live here moved into this long-press
            // menu (issue reported live: wanted delete off a standalone glyph and
            // onto the same long-press pattern every other album/playlist/
            // track-level action uses) — rename and "Edit order" moved in
            // alongside it rather than keeping separate top-bar affordances for
            // each. Tap is a deliberate no-op, same reasoning as AlbumDetailScreen's
            // artwork: this text had no tap behavior of its own before.
            LightText(
                text = title,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp())
                    .lightCombinedClickable(
                        onClick = {},
                        onLongClick = {
                            playlistActions.openMenu(
                                playlistId,
                                title,
                                // Flips reorder mode in place: the handles live on this screen's own track list.
                                editOrder = { viewModel.setReorderMode(true) },
                                onDeleted = { goBack() },
                            )
                        },
                    ),
            )

            ScreenList(viewModel.scrollPosition, uniformItemHeightGridUnits = 4.5f) {
                itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                    TrackRow(
                        track = track,
                        // Left-aligned, leading the title, matching QueueScreen's
                        // own reorder icons exactly (reported live) — only shows
                        // once "Edit order" is chosen from the playlist-level
                        // long-press menu (reported live: previously always
                        // visible for every track regardless of reorder mode).
                        leading = if (reorderMode) {
                            {
                                if (index > 0) {
                                    LightIcon(
                                        icon = LightIcons.UP,
                                        size = 1.5f,
                                        contentDescription = "Move up",
                                        modifier = Modifier
                                            .lightClickable(onClick = { viewModel.moveUp(index) })
                                            .padding(end = 0.5f.gridUnitsAsDp()),
                                    )
                                }
                                if (index < tracks.lastIndex) {
                                    LightIcon(
                                        icon = LightIcons.DOWN,
                                        size = 1.5f,
                                        contentDescription = "Move down",
                                        modifier = Modifier
                                            .lightClickable(onClick = { viewModel.moveDown(index) })
                                            .padding(end = 0.5f.gridUnitsAsDp()),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        onPlay = { trackActions.play(tracks, index) },
                        onOpenActions = {
                            trackActions.openMenu(
                                track,
                                alsoOffer = listOf(
                                    ActionMenuItem(
                                        icon = LightIcons.CLOSE,
                                        label = "Remove from playlist",
                                        onSelect = ActionMenuSelection.Perform {
                                            viewModel.removeTrack(index)
                                            null
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

// TrackRow (TrackRow.kt) is now the shared module — tap the title to play,
// long-press for the action menu (favorite, download, remove from playlist —
// issue #16). Reorder (up/down) only shows once "Edit order" is chosen from
// the playlist-level long-press menu — reported live: previously always
// visible for every track regardless of reorder mode — via TrackRow's
// `leading` slot, left-aligned ahead of the title, matching QueueScreen's own
// reorder icons exactly (also reported live).
