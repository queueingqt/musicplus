package com.musicplus.app

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

class FavoritesScreenViewModel(
    private val syncQueueRepository: SyncQueueRepository,
    listRefresher: ListRefresher,
) : CachedListViewModel(listRefresher, ListRefresher.Target.FAVORITES) {

    // See AppLibraryCache's doc — reads the already-live, process-lifetime cache instead of re-subscribing to observeFavorite*() on every
    // fresh per-visit ViewModel. One filter narrows all three lists.
    val filter = ListFilter(viewModelScope)
    val artists = filter.narrow(AppLibraryCache.favoriteArtists.value) { artist, query -> artist.name.containsIgnoringCase(query) }
    val albums = filter.narrow(AppLibraryCache.favoriteAlbums.value) { album, query -> album.name.containsIgnoringCase(query) }
    val tracks = filter.narrow(AppLibraryCache.favoriteTracks.value) { track, query -> track.title.containsIgnoringCase(query) }

    // Unfavoriting here naturally drops the row from the lists above — each is derived from observeFavorite*(), which only ever includes
    // starred items — so there's no separate "remove from this list" step beyond the same favorite toggle every other screen uses.
    suspend fun setArtistFavorite(id: String, favorite: Boolean) = syncQueueRepository.setArtistFavorite(id, favorite)
}

class FavoritesScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, FavoritesScreenViewModel>(activity) {

    override val viewModelClass = FavoritesScreenViewModel::class.java

    override fun createViewModel(): FavoritesScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return FavoritesScreenViewModel(graph.syncQueueRepository, graph.listRefresher)
    }

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val albums by viewModel.albums.collectAsState()
        val tracks by viewModel.tracks.collectAsState()
        val filter by viewModel.filter.query.collectAsState()
        val trackActions = rememberTrackActions(activity, lightContext)
        val albumActions = rememberAlbumActions(activity, lightContext)

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Favorites"),
                    rightButton = filterButton("favorites", filter, viewModel.filter::set),
                )
            },
        ) {
            if (artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) EmptyListNote("favorites", filter)
            ScreenList(viewModel.scrollPosition) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    LabelRow(
                        label = artist.nameLine,
                        onClick = { navigateTo({ a -> ArtistDetailScreen(a, artist.id) }) },
                        // Long-press for the way to remove it from favorites.
                        onLongClick = {
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
                    ArtRow(
                        lightContext = lightContext,
                        label = album.nameLine,
                        coverArtId = album.coverArtId,
                        onClick = { navigateTo({ a -> AlbumDetailScreen(a, album.id, album) }) },
                        onLongClick = { albumActions.openMenu(album.id, album.name, isFavorite = true) },
                    )
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        subtitle = track.serverLabel,
                        // Every row on this screen is definitionally favorited already (it's the Favorites list) — a star here would
                        // be redundant, not a gap.
                        showFavorite = false,
                        onPlay = { trackActions.play(listOf(track)) },
                        onOpenActions = { trackActions.openMenu(track) },
                    )
                }
            }
        }
    }
}
