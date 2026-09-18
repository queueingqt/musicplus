package com.musicplus.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        val scope = rememberCoroutineScope()
        val tracks by viewModel.tracks.collectAsState()
        val playlist by viewModel.playlist.collectAsState()
        val title = playlist?.name ?: "Playlist"

        // Cheap two-tap confirm (no dialog/modal primitive with Confirm+Cancel is
        // confirmed available in the SDK — LightFullscreenModal is message+single-
        // button-only) — first tap arms it, a second tap within 3s deletes, anything
        // else (navigating away, waiting it out) disarms it.
        var confirmDelete by remember { mutableStateOf(false) }
        LaunchedEffect(confirmDelete) {
            if (confirmDelete) {
                delay(3_000)
                confirmDelete = false
            }
        }

        fun trackDownloadActionItem(track: Track, status: DownloadStatus?): ActionMenuItem = ActionMenuItem(
            icon = if (status == DownloadStatus.COMPLETE) LightIcons.DOWNLOADED_ARROW else LightIcons.DOWNLOAD_ARROW,
            label = downloadStatusLabel(status),
            onSelect = ActionMenuSelection.Perform {
                trackDownloadActionItem(track, viewModel.toggleDownload(lightContext, track, status))
            },
        )

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(title),
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
            // Icon-only per this session's UI convention (no visible label next to
            // a self-explanatory icon) — the armed/confirm state is conveyed by
            // swapping the icon itself (TRASH -> ACCEPT) plus contentDescription,
            // not by adding a text label. No confirm/cancel dialog primitive is
            // confirmed available in the SDK (see the comment on `confirmDelete`
            // above), hence this two-tap pattern instead.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            ) {
                LightIcon(
                    icon = if (confirmDelete) LightIcons.ACCEPT else LightIcons.TRASH,
                    size = 1.5f,
                    contentDescription = if (confirmDelete) "Tap to confirm delete" else "Delete playlist",
                    modifier = Modifier.lightClickable {
                        if (confirmDelete) {
                            viewModel.delete { goBack() }
                        } else {
                            confirmDelete = true
                        }
                    },
                )
            }

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 4.5f) {
                itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                    val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                    val status by statusFlow.collectAsState(initial = null)
                    PlaylistTrackRow(
                        track = track,
                        canMoveUp = index > 0,
                        canMoveDown = index < tracks.lastIndex,
                        onPlay = {
                            scope.launch {
                                val graph = AppGraph.from(lightContext)
                                PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).play(tracks, index)
                                navigateTo(::PlayerScreen)
                            }
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
                                        trackDownloadActionItem(track, status?.status),
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
@Composable
private fun PlaylistTrackRow(
    track: Track,
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
            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightCombinedClickable(onClick = onPlay, onLongClick = onOpenActions),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** Tap semantics: QUEUED/DOWNLOADING/COMPLETE -> stop or remove; FAILED/null -> start. See AlbumDetailScreen's identical helper. */
private fun downloadStatusLabel(status: DownloadStatus?): String = when (status) {
    null -> "Download"
    DownloadStatus.QUEUED -> "Queued — tap to cancel"
    DownloadStatus.DOWNLOADING -> "Downloading — tap to cancel"
    DownloadStatus.COMPLETE -> "Downloaded — tap to remove"
    DownloadStatus.FAILED -> "Failed — tap to retry"
}
