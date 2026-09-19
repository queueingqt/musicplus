package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
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
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FavoritesScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val downloadRepository: DownloadRepository,
) : LightViewModel<Unit>() {

    // See AppLibraryCache's doc — reads the already-live, process-lifetime
    // cache instead of re-subscribing to libraryRepository.observeFavorite*()
    // on every fresh per-visit ViewModel.
    private val allArtists: StateFlow<List<Artist>> = AppLibraryCache.favoriteArtists.value
    private val allAlbums: StateFlow<List<Album>> = AppLibraryCache.favoriteAlbums.value
    private val allTracks: StateFlow<List<Track>> = AppLibraryCache.favoriteTracks.value

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    val artists: StateFlow<List<Artist>> = filteredBy(allArtists, _filter) { artist, q ->
        artist.name.contains(q, ignoreCase = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = filteredBy(allAlbums, _filter) { album, q ->
        album.name.contains(q, ignoreCase = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<Track>> = filteredBy(allTracks, _filter) { track, q ->
        track.title.contains(q, ignoreCase = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    // Unfavoriting here naturally drops the row from artists/albums/tracks
    // above — each is derived from observeFavorite*(), which only ever
    // includes starred items — so there's no separate "remove from this
    // list" step needed beyond the same favorite toggle every other screen
    // already uses.
    suspend fun setArtistFavorite(id: String, favorite: Boolean) = syncQueueRepository.setArtistFavorite(id, favorite)
    suspend fun setAlbumFavorite(id: String, favorite: Boolean) = syncQueueRepository.setAlbumFavorite(id, favorite)
    suspend fun setTrackFavorite(id: String, favorite: Boolean) = syncQueueRepository.setTrackFavorite(id, favorite)

    // Same pattern as AlbumListScreenViewModel's identical trio — see its doc:
    // SelfLoadingTrackList refreshes an album's tracks before reading them, so
    // "Download album"/"Add album to queue" work here even for an album never
    // opened via its own detail screen (which is exactly how a favorited album
    // is commonly reached — straight from this list, not via AlbumDetailScreen).
    fun albumDownloadState(albumId: String): Flow<TrackListDownloadState> =
        SelfLoadingTrackList.forAlbum(libraryRepository, albumId).observeDownloadState(downloadRepository)

    suspend fun toggleAlbumDownload(lightContext: SealedLightContext, albumId: String): TrackListDownloadState =
        SelfLoadingTrackList.forAlbum(libraryRepository, albumId).toggleDownload(lightContext, downloadRepository)

    suspend fun tracksForAlbum(albumId: String): List<Track> =
        SelfLoadingTrackList.forAlbum(libraryRepository, albumId).tracks()

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

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

class FavoritesScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, FavoritesScreenViewModel>(activity) {

    override val viewModelClass = FavoritesScreenViewModel::class.java

    override fun createViewModel(): FavoritesScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return FavoritesScreenViewModel(graph.libraryRepository, graph.syncQueueRepository, graph.downloadRepository)
    }

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val albums by viewModel.albums.collectAsState()
        val tracks by viewModel.tracks.collectAsState()
        val filter by viewModel.filter.collectAsState()

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Favorites"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = {
                            navigateTo({ a -> TextEditScreen(a, "Search favorites", filter) }) { result ->
                                viewModel.setFilter(result)
                            }
                        },
                        contentDescription = "Search favorites",
                    ),
                )
            },
        ) {
            val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
            // Inside, not the default Outside — see ScrollbarGutter.kt's doc
            // (issue #39). The bug isn't limited to a trailing icon: any
            // row's available width is unstable between the first frame
            // (Outside's gutter not reserved yet) and the next (it is), so a
            // maxLines=1/Ellipsis title (FavoriteRowWithArt's album name)
            // briefly renders wider — closer to the true edge — then visibly
            // settles narrower once the real gutter reserves its space.
            // Reported live.
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 3f,
            ) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    FavoriteRow(
                        label = artist.name,
                        onClick = { navigateTo({ a -> ArtistDetailScreen(a, artist.id) }) },
                        onOpenActions = {
                            navigateTo({ a ->
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = artist.name,
                                    items = listOf(
                                        favoriteActionItem(isFavorite = true) { favorite ->
                                            viewModel.setArtistFavorite(artist.id, favorite)
                                        },
                                    ),
                                )
                            })
                        },
                    )
                }
                item { SectionHeader("Albums") }
                items(albums, key = { "album-${it.id}" }) { album ->
                    FavoriteRowWithArt(
                        lightContext = lightContext,
                        label = album.name,
                        coverArtUrl = album.coverArtUrl,
                        onClick = { navigateTo({ a -> AlbumDetailScreen(a, album.id, album) }) },
                        onOpenActions = {
                            navigateTo({ a ->
                                val addToQueueItem = addToQueueActionItem("Add album to queue", playbackRepository(activity, lightContext)) {
                                    viewModel.tracksForAlbum(album.id)
                                }
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = album.name,
                                    items = listOf(
                                        favoriteActionItem(isFavorite = true) { favorite ->
                                            viewModel.setAlbumFavorite(album.id, favorite)
                                        },
                                        // Real state only starts being read once this menu is
                                        // actually open (via liveUpdates below) — same pattern
                                        // as AlbumListScreen's identical menu item.
                                        trackListDownloadActionItem("album", TrackListDownloadState.NONE) { viewModel.toggleAlbumDownload(lightContext, album.id) }.copy(
                                            liveUpdates = viewModel.albumDownloadState(album.id).map { s ->
                                                trackListDownloadActionItem("album", s) { viewModel.toggleAlbumDownload(lightContext, album.id) }
                                            },
                                        ),
                                        addToQueueItem,
                                    ),
                                )
                            })
                        },
                    )
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        downloadStatus = track.downloadStatus,
                        // Every row on this screen is definitionally favorited
                        // already (it's the Favorites list) — a star here would
                        // be redundant, not a gap. The download glyph is a real
                        // gap fix: this row previously showed neither.
                        showFavorite = false,
                        onPlay = {
                            // playAsync() updates title/art synchronously and
                            // continues loading on PlaybackRepository's own scope,
                            // so navigating away immediately after is safe — see
                            // PlaybackRepository.playAsync's doc.
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
                                        favoriteActionItem(isFavorite = true) { favorite ->
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
                                        // Wasn't here before — reported live, same
                                        // gap as the missing row glyph.
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

@Composable
private fun SectionHeader(title: String) {
    LightText(
        text = title,
        variant = LightTextVariant.Heading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** Tap to open (unchanged); long-press for the action menu — was tap-only, with no way to remove from favorites short of going to find the artist elsewhere. Reported live. */
@Composable
private fun FavoriteRow(label: String, onClick: () -> Unit, onOpenActions: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        // end matches the SDK's own scrollbar track width — see the
        // LightLazyScrollView call site above for why this is fixed rather
        // than conditional on whether a scrollbar happens to show.
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions)
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
    )
}

// TrackRow (TrackRow.kt) is now the shared module for the Tracks section
// above — see its call site for why showFavorite = false here specifically.

/** Favorite album rows — the ones with cover art (see issue #9 scope; artist rows stay [FavoriteRow]). Tap to open; long-press for the action menu (see FavoriteRow's doc — same gap, same fix). */
@Composable
private fun FavoriteRowWithArt(
    lightContext: SealedLightContext,
    label: String,
    coverArtUrl: String?,
    onClick: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions)
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            lightContext = lightContext,
            url = coverArtUrl,
            size = 2.5f.gridUnitsAsDp(),
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
