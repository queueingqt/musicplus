package com.musicplus.app

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadEntity
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumDetailScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val albumId: String,
) : LightViewModel<Unit>() {

    val tracks: StateFlow<List<Track>> = libraryRepository.observeTracksByAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Depends on the album already being cached locally — true once AlbumListScreen's
    // refreshAlbumList() or ArtistDetailScreen's refreshArtistDetail() has run, since
    // both upsert album rows; there's no observeAlbumById on LibraryRepository. Title
    // falls back to a track's own albumName in the Composable when this is still null
    // (e.g. arriving here straight from a search result).
    val album: StateFlow<Album?> = libraryRepository.observeAlbums()
        .map { albums -> albums.find { it.id == albumId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Drives the album-level download action's icon/label: NONE (nothing downloaded),
    // SOME (a mix — shows as the "start" icon, tapping downloads the rest),
    // ALL (every track downloaded — shows as complete, tapping removes all).
    val albumDownloadState: StateFlow<AlbumDownloadState> =
        combine(tracks, downloadRepository.observeAll()) { trackList, downloads ->
            if (trackList.isEmpty()) return@combine AlbumDownloadState.NONE
            val downloadedIds = downloads.filter { it.status == DownloadStatus.COMPLETE }.map { it.songId }.toSet()
            when {
                downloadedIds.containsAll(trackList.map { it.id }) -> AlbumDownloadState.ALL
                trackList.any { it.id in downloadedIds } -> AlbumDownloadState.SOME
                else -> AlbumDownloadState.NONE
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumDownloadState.NONE)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshAlbumDetail(albumId) }
    }

    fun toggleFavorite() {
        val isFavorite = album.value?.isFavorite ?: false
        viewModelScope.launch { libraryRepository.setAlbumFavorite(albumId, !isFavorite) }
    }

    fun downloadStatus(songId: String): Flow<DownloadEntity?> = downloadRepository.observeStatus(songId)

    /**
     * This button is now the only download control (no separate Downloads screen —
     * status lives inline per track everywhere), so it has to do double duty:
     * enqueue when there's nothing downloaded/in-flight, and stop/remove otherwise.
     * `DownloadRepository.cancel` already deletes both the local file and the DB
     * row regardless of job state, so it doubles as "remove local copy" for a
     * COMPLETE download, not just "abort an in-flight one".
     */
    fun toggleDownload(lightContext: SealedLightContext, track: Track, currentStatus: DownloadStatus?) {
        viewModelScope.launch {
            when (currentStatus) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETE ->
                    downloadRepository.cancel(lightContext, track.id)
                DownloadStatus.FAILED, null -> downloadRepository.enqueue(lightContext, track)
            }
        }
    }

    /** ALL -> remove every downloaded track; NONE/SOME -> download whatever isn't already complete. */
    fun toggleAlbumDownload(lightContext: SealedLightContext) {
        viewModelScope.launch {
            val currentTracks = tracks.value
            when (albumDownloadState.value) {
                AlbumDownloadState.ALL -> currentTracks.forEach { downloadRepository.cancel(lightContext, it.id) }
                AlbumDownloadState.NONE, AlbumDownloadState.SOME -> currentTracks.forEach { downloadRepository.enqueue(lightContext, it) }
            }
        }
    }
}

enum class AlbumDownloadState { NONE, SOME, ALL }

/**
 * `activity` is retained as a property (same reasoning as PlayerScreen's
 * `sealedActivity`) because the per-track play action needs it for
 * `PlaybackRepositoryHolder.get(...)` from inside `Content()`.
 */
class AlbumDetailScreen(
    private val activity: SealedLightActivity,
    private val albumId: String,
) : LightScreen<Unit, AlbumDetailScreenViewModel>(activity) {

    override val viewModelClass = AlbumDetailScreenViewModel::class.java

    override fun createViewModel(): AlbumDetailScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return AlbumDetailScreenViewModel(graph.libraryRepository, graph.downloadRepository, albumId)
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val tracks by viewModel.tracks.collectAsState()
        val album by viewModel.album.collectAsState()
        val albumDownloadState by viewModel.albumDownloadState.collectAsState()
        val title = album?.name ?: tracks.firstOrNull()?.albumName ?: "Album"

        // Plain back + centered title now — the 3 album-level action icons that used to
        // live here (favorite, download, add-to-queue, added earlier this session) moved
        // to a long-press on the artwork below instead (issue #16). See the long-press
        // handler's own comment for why the artwork rather than the title, and why a
        // long-press at all rather than leaving them in the top bar.
        LightwaveScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AlbumArt(
                    lightContext = lightContext,
                    url = album?.coverArtUrl,
                    size = 9f.gridUnitsAsDp(),
                    placeholderIconSize = 4f,
                    modifier = Modifier
                        .padding(vertical = 1f.gridUnitsAsDp())
                        // Long-press opens the album-level actions menu (favorite, download,
                        // add to queue — the 3 icons that used to sit in the top bar). Chose
                        // the artwork over the title as the long-press target: it's the
                        // biggest, most obviously "this is the thing this screen is about"
                        // element on the screen, matches the long-press-a-photo/icon pattern
                        // this device otherwise has no equivalent of, and — unlike the title —
                        // it isn't text that already has its own reason to exist as plain
                        // copy. Tap is a deliberate no-op: the artwork never had tap behavior
                        // of its own before this change, and this doesn't add one.
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                navigateTo({ a ->
                                    val isFavorite = album?.isFavorite == true
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = title,
                                        items = listOf(
                                            ActionMenuItem(
                                                icon = if (isFavorite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                                                label = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                                onSelect = ActionMenuSelection.Perform { viewModel.toggleFavorite() },
                                            ),
                                            ActionMenuItem(
                                                icon = when (albumDownloadState) {
                                                    AlbumDownloadState.ALL -> LightIcons.DOWNLOADED_ARROW
                                                    AlbumDownloadState.SOME, AlbumDownloadState.NONE -> LightIcons.DOWNLOAD_ARROW
                                                },
                                                label = when (albumDownloadState) {
                                                    AlbumDownloadState.ALL -> "Downloaded — remove"
                                                    AlbumDownloadState.SOME -> "Some tracks downloaded — download the rest"
                                                    AlbumDownloadState.NONE -> "Download album"
                                                },
                                                onSelect = ActionMenuSelection.Perform { viewModel.toggleAlbumDownload(lightContext) },
                                            ),
                                            ActionMenuItem(
                                                icon = LightIcons.ADD,
                                                label = "Add album to queue",
                                                onSelect = ActionMenuSelection.Perform {
                                                    val graph = AppGraph.from(lightContext)
                                                    PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).addToQueue(tracks)
                                                },
                                            ),
                                        ),
                                    )
                                })
                            },
                        ),
                )
            }

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                    val status by statusFlow.collectAsState(initial = null)
                    TrackRow(
                        track = track,
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
                                        ActionMenuItem(
                                            icon = if (track.isFavorite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                                            label = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                                            onSelect = ActionMenuSelection.Perform {
                                                AppGraph.from(lightContext).libraryRepository.setTrackFavorite(track.id, !track.isFavorite)
                                            },
                                        ),
                                        ActionMenuItem(
                                            icon = LightIcons.ADD,
                                            label = "Add to queue",
                                            onSelect = ActionMenuSelection.Perform {
                                                val graph = AppGraph.from(lightContext)
                                                PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).addToQueue(listOf(track))
                                            },
                                        ),
                                        ActionMenuItem(
                                            icon = LightIcons.LIST,
                                            label = "Add to playlist",
                                            onSelect = ActionMenuSelection.Navigate {
                                                navigateTo({ a2 -> PlaylistPickerScreen(a2, track.id) })
                                            },
                                        ),
                                        ActionMenuItem(
                                            icon = downloadIcon(status),
                                            label = downloadStatusLabel(status),
                                            onSelect = ActionMenuSelection.Perform {
                                                viewModel.toggleDownload(lightContext, track, status?.status)
                                            },
                                        ),
                                    ),
                                )
                            })
                        },
                    )
                }
            }
        }
    }
}

/** Tap to play (unchanged); long-press for the full action menu (favorite, queue, playlist, download — issue #16). */
@Composable
private fun TrackRow(
    track: Track,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onOpenActions)
            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Three real visual states, not two: QUEUED/DOWNLOADING now render distinctly from
 * both "not downloaded" and "downloaded" instead of only toggling between
 * DOWNLOAD_ARROW/DOWNLOADED_ARROW. Uses REFRESH for "in progress" — LOOP was tried
 * first but is the exact same icon the Now Playing screen uses for Repeat, which
 * on-device looked like a stray repeat toggle appearing on tracks whenever an
 * album download was running. There's no dedicated spinner/progress icon in
 * LightIcons; REFRESH isn't used anywhere else in this app, so it doesn't collide.
 */
private fun downloadIcon(status: DownloadEntity?) = when (status?.status) {
    DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.REFRESH
    DownloadStatus.FAILED, null -> LightIcons.DOWNLOAD_ARROW
}

/** Tap semantics: QUEUED/DOWNLOADING/COMPLETE -> stop or remove; FAILED/null -> start. See `toggleDownload`. */
private fun downloadStatusLabel(status: DownloadEntity?): String = when (status?.status) {
    null -> "Download"
    DownloadStatus.QUEUED -> "Queued — tap to cancel"
    DownloadStatus.DOWNLOADING -> "Downloading — tap to cancel"
    DownloadStatus.COMPLETE -> "Downloaded — tap to remove"
    DownloadStatus.FAILED -> "Failed — tap to retry"
}
