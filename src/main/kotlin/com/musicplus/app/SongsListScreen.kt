package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.playbackRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Flat, unscoped "every song in the library" browse list (previously
 * requested, never actually delivered) — distinct from Favorites' Tracks
 * section (favorited only) and Search (server-side query match only).
 * [LibraryRepository.observeAllTracks]'s local cache is otherwise only ever
 * as complete as whichever albums/playlists/searches happen to have been
 * visited, so [LibraryRepository.refreshAllSongs] — the one real "fetch
 * everything" sync this app does, since Subsonic has no direct getAllSongs
 * endpoint — runs at app start and on reconnect, and again in the background
 * each time this page opens. The page itself never waits for it: it reads
 * the warmed cache (see AppLibraryCache's doc) and the refresh's additions and
 * deletions reach the list through the cache (see ListRefresher's doc).
 */
class SongsListScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val listRefresher: ListRefresher,
) : LightViewModel<Unit>() {

    // The page opens straight from the cache; this re-checks the server in the
    // background and any additions or deletions arrive through the cache
    // itself — no spinner. See ListRefresher's doc.
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        listRefresher.refreshOnOpen(ListRefresher.Target.SONGS)
    }


    // See AppLibraryCache's doc — reads the already-live, process-lifetime
    // cache instead of re-subscribing to libraryRepository.observeAllTracks()
    // on every fresh per-visit ViewModel. This is the collection the warmed-
    // cache fix was originally measured against (several real seconds on a
    // warm process, several thousand tracks) before being generalized to
    // Albums/Artists/Playlists too.
    private val allTracks: StateFlow<List<Track>> = AppLibraryCache.allTracks.value

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    // Client-side filter over the already-cached list, same convention as
    // AlbumListScreen/ArtistListScreen — not a server round-trip.
    val tracks: StateFlow<List<Track>> = filteredBy(allTracks, _filter) { track, query ->
        track.title.contains(query, ignoreCase = true) || track.artistName?.contains(query, ignoreCase = true) == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

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
        return SongsListScreenViewModel(graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository, graph.listRefresher)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val filter by viewModel.filter.collectAsState()

        MusicPlusScaffold(
            screen = this,
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
                        TrackRow(
                            track = track,
                            downloadStatus = track.downloadStatus,
                            subtitle = track.artistName ?: "Unknown artist",
                            onPlay = {
                                // playAsync() updates title/art synchronously and
                                // continues loading on PlaybackRepository's own
                                // scope, so navigating away immediately after is
                                // safe — see PlaybackRepository.playAsync's doc.
                                val playback = playbackRepository(activity, lightContext)
                                playback.playAsync(listOf(track), 0)
                                navigateTo(::PlayerScreen)
                            },
                            onOpenActions = {
                                navigateTo({ a ->
                                    val addToQueueItem = addToQueueActionItem("Add to queue", playbackRepository(activity, lightContext)) {
                                        listOf(track)
                                    }
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
                                            trackDownloadActionItem(track.downloadStatus) { newStatus ->
                                                viewModel.toggleDownload(lightContext, track, newStatus)
                                            }.copy(
                                                liveUpdates = AppGraph.from(lightContext).downloadRepository.observeStatus(track.id).map { entity ->
                                                    trackDownloadActionItem(entity?.status) { newStatus ->
                                                        viewModel.toggleDownload(lightContext, track, newStatus)
                                                    }
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
}

