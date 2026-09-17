package com.lightwave.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.DownloadEntity
import com.lightwave.app.data.DownloadRepository
import com.lightwave.app.data.DownloadStatus
import com.lightwave.app.data.LibraryRepository
import com.lightwave.app.data.PlaybackRepositoryHolder
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

    // Drives the album-level download button's icon: NONE (nothing downloaded),
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

        LightwaveTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), center = LightTopBarCenter.Text(title))

            // Favorite + album-level download, inline with the album title — icon-only,
            // no text labels (self-explanatory iconography).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(text = title, variant = LightTextVariant.Detail, modifier = Modifier.weight(1f))
                LightIcon(
                    icon = if (album?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                    size = 1.5f,
                    contentDescription = if (album?.isFavorite == true) "Favorited" else "Favorite",
                    modifier = Modifier
                        .lightClickable { viewModel.toggleFavorite() }
                        .padding(horizontal = 0.5f.gridUnitsAsDp()),
                )
                LightIcon(
                    icon = when (albumDownloadState) {
                        AlbumDownloadState.ALL -> LightIcons.DOWNLOADED_ARROW
                        AlbumDownloadState.SOME, AlbumDownloadState.NONE -> LightIcons.DOWNLOAD_ARROW
                    },
                    size = 1.5f,
                    contentDescription = when (albumDownloadState) {
                        AlbumDownloadState.ALL -> "Album downloaded — tap to remove"
                        AlbumDownloadState.SOME -> "Some tracks downloaded — tap to download the rest"
                        AlbumDownloadState.NONE -> "Download album"
                    },
                    modifier = Modifier
                        .lightClickable { viewModel.toggleAlbumDownload(lightContext) }
                        .padding(horizontal = 0.5f.gridUnitsAsDp()),
                )
            }

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                    val status by statusFlow.collectAsState(initial = null)
                    TrackRow(
                        track = track,
                        status = status,
                        onPlay = {
                            scope.launch {
                                val graph = AppGraph.from(lightContext)
                                PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).play(tracks, index)
                                navigateTo(::PlayerScreen)
                            }
                        },
                        onToggleFavorite = {
                            scope.launch { AppGraph.from(lightContext).libraryRepository.setTrackFavorite(track.id, !track.isFavorite) }
                        },
                        onDownload = { viewModel.toggleDownload(lightContext, track, status?.status) },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun TrackRow(track: Track, status: DownloadEntity?, onPlay: () -> Unit, onToggleFavorite: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onPlay),
        )
        LightIcon(
            icon = if (track.isFavorite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
            size = 1.5f,
            contentDescription = if (track.isFavorite) "Favorited" else "Favorite",
            modifier = Modifier
                .lightClickable(onClick = onToggleFavorite)
                .padding(horizontal = 0.5f.gridUnitsAsDp()),
        )
        LightIcon(
            icon = downloadIcon(status),
            size = 1.5f,
            contentDescription = downloadStatusLabel(status),
            modifier = Modifier.lightClickable(onClick = onDownload),
        )
    }
}

/**
 * Three real visual states, not two: QUEUED/DOWNLOADING now render distinctly from
 * both "not downloaded" and "downloaded" instead of only toggling between
 * DOWNLOAD_ARROW/DOWNLOADED_ARROW — LOOP stands in for "in progress" since there's
 * no confirmed spinner/progress-specific icon in LightIcons.
 */
private fun downloadIcon(status: DownloadEntity?) = when (status?.status) {
    DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.LOOP
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
