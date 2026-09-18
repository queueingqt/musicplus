package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Flat, unscoped "every song in the library" browse list (previously
 * requested, never actually delivered) — distinct from Favorites' Tracks
 * section (favorited only) and Search (server-side query match only).
 * [LibraryRepository.observeAllTracks]'s local cache is otherwise only ever
 * as complete as whichever albums/playlists/searches happen to have been
 * visited, so this screen also drives [LibraryRepository.refreshAllSongs] —
 * the one real "fetch everything" sync this app does, since Subsonic has no
 * direct getAllSongs endpoint.
 */
class SongsListScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
) : LightViewModel<Unit>() {

    private val allTracks: StateFlow<List<Track>> = libraryRepository.observeAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    // Client-side filter over the already-cached list, same convention as
    // AlbumListScreen/ArtistListScreen — not a server round-trip.
    val tracks: StateFlow<List<Track>> = combine(allTracks, _filter) { list, query ->
        if (query.isBlank()) list
        else list.filter { it.title.contains(query, ignoreCase = true) || it.artistName?.contains(query, ignoreCase = true) == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshAllSongs() }
    }

    fun downloadStatus(songId: String): Flow<DownloadEntity?> = downloadRepository.observeStatus(songId)

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

    suspend fun setTrackFavorite(id: String, favorite: Boolean) = syncQueueRepository.setTrackFavorite(id, favorite)

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

class SongsListScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, SongsListScreenViewModel>(activity) {

    override val viewModelClass = SongsListScreenViewModel::class.java

    override fun createViewModel(): SongsListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return SongsListScreenViewModel(graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val filter by viewModel.filter.collectAsState()

        fun downloadIcon(status: DownloadStatus?) = when (status) {
            DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.REFRESH
            DownloadStatus.FAILED, null -> LightIcons.DOWNLOAD_ARROW
        }

        fun downloadLabel(status: DownloadStatus?): String = when (status) {
            null -> "Download"
            DownloadStatus.QUEUED -> "Queued — tap to cancel"
            DownloadStatus.DOWNLOADING -> "Downloading — tap to cancel"
            DownloadStatus.COMPLETE -> "Downloaded — tap to remove"
            DownloadStatus.FAILED -> "Failed — tap to retry"
        }

        fun trackDownloadActionItem(track: Track, status: DownloadStatus?): ActionMenuItem = ActionMenuItem(
            key = "download",
            icon = downloadIcon(status),
            label = downloadLabel(status),
            onSelect = ActionMenuSelection.Perform {
                trackDownloadActionItem(track, viewModel.toggleDownload(lightContext, track, status))
            },
        )

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Songs"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = {
                            navigateTo({ a -> TextEditScreen(a, "Search songs", filter) }) { result ->
                                viewModel.setFilter(result)
                            }
                        },
                        contentDescription = "Search songs",
                    ),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            if (tracks.isEmpty()) {
                LightText(
                    text = "No songs yet",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                )
            } else {
                // Inside, not Outside — see AlbumDetailScreen's identical call
                // site for why (Outside's gutter width isn't known until after
                // first layout, so trailing per-row content briefly renders
                // full-width then jumps left once it appears; SongRow reserves
                // the same width itself, unconditionally, instead).
                val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
                LightLazyScrollView(
                    modifier = Modifier.fillMaxWidth(),
                    scrollBarPosition = LightScrollBarPosition.Inside,
                    listState = listState,
                    uniformItemHeightGridUnits = 3f,
                ) {
                    items(tracks, key = { it.id }) { track ->
                        val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                        val status by statusFlow.collectAsState(initial = null)
                        SongRow(
                            track = track,
                            downloadStatus = status?.status,
                            onPlay = {
                                // playAsync() updates title/art synchronously and
                                // continues loading on PlaybackRepository's own
                                // scope, so navigating away immediately after is
                                // safe — see PlaybackRepository.playAsync's doc.
                                val graph = AppGraph.from(lightContext)
                                val playback = PlaybackRepositoryHolder.get(activity, graph, lightContext.filesDir)
                                playback.playAsync(listOf(track), 0)
                                navigateTo(::PlayerScreen)
                            },
                            onOpenActions = {
                                navigateTo({ a ->
                                    lateinit var addToQueueItem: ActionMenuItem
                                    addToQueueItem = ActionMenuItem(
                                        icon = LightIcons.ADD,
                                        label = "Add to queue",
                                        onSelect = ActionMenuSelection.Perform {
                                            val graph = AppGraph.from(lightContext)
                                            PlaybackRepositoryHolder.get(activity, graph, lightContext.filesDir).addToQueue(listOf(track))
                                            addToQueueItem
                                        },
                                    )
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = track.title,
                                        items = listOf(
                                            favoriteActionItem(track.isFavorite) { favorite ->
                                                viewModel.setTrackFavorite(track.id, favorite)
                                            },
                                            addToQueueItem,
                                            ActionMenuItem(
                                                icon = LightIcons.LIST,
                                                label = "Add to playlist",
                                                onSelect = ActionMenuSelection.Navigate {
                                                    navigateTo({ a2 -> PlaylistPickerScreen(a2, track.id) })
                                                },
                                            ),
                                            trackDownloadActionItem(track, status?.status).copy(
                                                liveUpdates = viewModel.downloadStatus(track.id).map { trackDownloadActionItem(track, it?.status) },
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
}

/** Same shape as AlbumDetailScreen's TrackRow (tap to play, long-press for actions, favorite/download glyphs at a glance) plus the artist name, since this list spans every album. */
@Composable
private fun SongRow(
    track: Track,
    downloadStatus: DownloadStatus?,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onPlay, onLongClick = onOpenActions)
            .padding(top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = track.artistName ?: "Unknown artist",
            variant = LightTextVariant.Fine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
        )
        if (track.isFavorite) {
            LightIcon(
                icon = LightIcons.STAR,
                size = 1.2f,
                contentDescription = "Favorited",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
        if (downloadStatus != null) {
            LightIcon(
                icon = when (downloadStatus) {
                    DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.REFRESH
                    DownloadStatus.FAILED -> LightIcons.DOWNLOAD_ARROW
                },
                size = 1.2f,
                contentDescription = "Download status",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
