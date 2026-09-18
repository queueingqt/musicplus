package com.musicplus.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadEntity
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.musicplus.app.data.PlaylistRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class PlaylistDetailScreenViewModel(
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val playlistId: String,
) : LightViewModel<Unit>() {

    val tracks: StateFlow<List<Track>> = playlistRepository.observeTracks(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlist: StateFlow<Playlist?> = playlistRepository.observePlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { playlistRepository.refreshPlaylistDetail(playlistId) }
    }

    fun downloadStatus(songId: String): Flow<DownloadEntity?> = downloadRepository.observeStatus(songId)

    /**
     * Same enqueue/cancel-doubles-as-remove semantics as
     * AlbumDetailScreenViewModel.toggleDownload. Suspend and returns the
     * resulting status (rather than fire-and-forget) so the action menu row
     * that triggers this can update itself in place afterward.
     */
    suspend fun toggleDownload(lightContext: SealedLightContext, track: Track, currentStatus: DownloadStatus?): DownloadStatus? =
        when (currentStatus) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETE -> {
                downloadRepository.cancel(lightContext, track.id)
                null
            }
            DownloadStatus.FAILED, null -> {
                downloadRepository.enqueue(lightContext, track)
                DownloadStatus.QUEUED
            }
        }

    /** [position] is the track's zero-based index in the currently-displayed (i.e. server) order. Suspend, same reasoning as [toggleDownload]. */
    suspend fun removeTrack(position: Int) {
        syncQueueRepository.removeTrack(playlistId, position)
    }

    fun moveUp(position: Int) {
        viewModelScope.launch { syncQueueRepository.moveTrackUp(playlistId, position) }
    }

    fun moveDown(position: Int) {
        viewModelScope.launch { syncQueueRepository.moveTrackDown(playlistId, position) }
    }

    fun rename(name: String) {
        viewModelScope.launch { syncQueueRepository.renamePlaylist(playlistId, name) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            syncQueueRepository.deletePlaylist(playlistId)
            onDeleted()
        }
    }

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

/**
 * `activity` is retained as a property for the same reason as AlbumDetailScreen's —
 * per-track play needs it for `PlaybackRepositoryHolder.get(...)` from `Content()`.
 */
class PlaylistDetailScreen(
    private val activity: SealedLightActivity,
    private val playlistId: String,
) : LightScreen<Unit, PlaylistDetailScreenViewModel>(activity) {

    override val viewModelClass = PlaylistDetailScreenViewModel::class.java

    override fun createViewModel(): PlaylistDetailScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return PlaylistDetailScreenViewModel(graph.playlistRepository, graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository, playlistId)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val playlist by viewModel.playlist.collectAsState()
        val title = playlist?.name ?: "Playlist"

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    // Generic label, not the playlist's own name — that's shown
                    // (and interactive) as the body heading below. Reported live:
                    // showing the exact same name in both places read as a
                    // double title once the body heading was added.
                    center = LightTopBarCenter.Text("Playlist"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.PENCIL,
                        contentDescription = "Rename playlist",
                        onClick = {
                            navigateTo({ a -> TextEditScreen(a, "Playlist name", title) }) { newName ->
                                if (!newName.isNullOrBlank()) viewModel.rename(newName)
                            }
                        },
                    ),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            // The trash icon that used to live here moved into the long-press
            // action menu (issue reported live: wanted delete off a standalone
            // glyph and onto the same long-press pattern every other
            // album/playlist/track-level action uses). This title is the
            // long-press target for it — the screen had no long-press target
            // for playlist-level actions at all before, unlike AlbumDetailScreen's
            // artwork. Tap is a deliberate no-op, same reasoning as that
            // screen's artwork: this text had no tap behavior of its own before.
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
                            navigateTo({ a ->
                                // lateinit self-reference, not a plain `null` return — Perform's
                                // contract is "null means this row no longer applies at all, drop
                                // it from the menu" (see ActionsMenuScreen.kt's doc). This row still
                                // applies regardless of whether the confirm dialog it just opened
                                // gets confirmed or cancelled, so it must return itself, not null.
                                // Returning null here (copied from ServerSettingsScreen's identical
                                // Delete row, which has the same latent bug) dropped this row the
                                // instant it was tapped — invisible there since it's one of several
                                // rows, but this menu has only this one row, so it went blank.
                                // Reported live, 2026-09-18.
                                lateinit var deleteItem: ActionMenuItem
                                deleteItem = ActionMenuItem(
                                    icon = LightIcons.TRASH,
                                    label = "Delete playlist",
                                    onSelect = ActionMenuSelection.Perform {
                                        LightModalManager.show(
                                            ConfirmModal(
                                                title = "Delete \"$title\"?",
                                                message = "This removes the playlist. The tracks themselves aren't affected.",
                                                confirmContentDescription = "Delete playlist",
                                                onConfirm = { viewModel.delete { goBack() } },
                                            ),
                                            duration = 30.seconds,
                                        )
                                        deleteItem
                                    },
                                )
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = title,
                                    items = listOf(deleteItem),
                                )
                            })
                        },
                    ),
            )

            // Inside, not Outside — see AlbumDetailScreen's identical call site
            // for why (Outside's gutter width isn't known until after first
            // layout, so trailing per-row content briefly renders full-width
            // then jumps left once it appears; PlaylistTrackRow reserves the
            // same width itself, unconditionally, instead).
            val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 4.5f,
            ) {
                itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                    val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                    val status by statusFlow.collectAsState(initial = null)
                    PlaylistTrackRow(
                        track = track,
                        downloadStatus = status?.status,
                        canMoveUp = index > 0,
                        canMoveDown = index < tracks.lastIndex,
                        onPlay = {
                            // playAsync() updates title/art synchronously and
                            // continues loading on PlaybackRepository's own scope,
                            // so navigating away immediately after is safe — see
                            // PlaybackRepository.playAsync's doc.
                            val graph = AppGraph.from(lightContext)
                            val playback = PlaybackRepositoryHolder.get(activity, graph, lightContext.filesDir)
                            playback.playAsync(tracks, index)
                            navigateTo(::PlayerScreen)
                        },
                        onOpenActions = {
                            navigateTo({ a ->
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = track.title,
                                    items = listOf(
                                        favoriteActionItem(track.isFavorite) { favorite ->
                                            AppGraph.from(lightContext).syncQueueRepository.setTrackFavorite(track.id, favorite)
                                        },
                                        trackDownloadActionItem(status?.status) { newStatus ->
                                            viewModel.toggleDownload(lightContext, track, newStatus)
                                        }.copy(
                                            liveUpdates = viewModel.downloadStatus(track.id).map { entity ->
                                                trackDownloadActionItem(entity?.status) { newStatus ->
                                                    viewModel.toggleDownload(lightContext, track, newStatus)
                                                }
                                            },
                                        ),
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
                            })
                        },
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) },
                    )
                }
            }
        }
    }
}

/**
 * Tap the title to play (unchanged); long-press it for the action menu
 * (favorite, download, remove from playlist — issue #16). Reorder (up/down)
 * deliberately stays as inline icons rather than moving into that menu: it's
 * a repeated, in-context operation — someone repositioning a track taps it
 * several times in a row — unlike the other three actions here, which are
 * single-shot. Routing every nudge through long-press -> menu -> tap ->
 * auto-return -> long-press again would make the single most repetitive
 * action on this screen also the most expensive one to perform.
 */
/** Favorite/download glyphs shown only when they have something to say — see AlbumDetailScreen's TrackRow doc for why (issue reported live: moving those actions behind long-press also removed any at-a-glance state). */
@Composable
private fun PlaylistTrackRow(
    track: Track,
    downloadStatus: DownloadStatus?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightCombinedClickable(onClick = onPlay, onLongClick = onOpenActions),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = track.title,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.isFavorite) {
                LightIcon(
                    icon = LightIcons.STAR,
                    size = 1.2f,
                    contentDescription = "Favorited",
                    modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                )
            }
            // This used to distinguish only COMPLETE vs. everything else, so a
            // track actively downloading inside a playlist showed the exact same
            // icon as one not yet started — downloadStatusIcon (TrackActionItems.kt)
            // is the 3-state version every other screen with a per-track download
            // glyph (AlbumDetailScreen, SongsListScreen) already used.
            if (downloadStatus != null) {
                LightIcon(
                    icon = downloadStatusIcon(downloadStatus),
                    size = 1.2f,
                    contentDescription = downloadStatusLabel(downloadStatus),
                    modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (canMoveUp) {
                LightIcon(
                    icon = LightIcons.UP,
                    size = 1.5f,
                    contentDescription = "Move up",
                    modifier = Modifier.lightClickable(onClick = onMoveUp),
                )
            }
            if (canMoveDown) {
                LightIcon(
                    icon = LightIcons.DOWN,
                    size = 1.5f,
                    contentDescription = "Move down",
                    modifier = Modifier
                        .lightClickable(onClick = onMoveDown)
                        .padding(start = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}
