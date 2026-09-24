package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppAvailability
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ListRefresher
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

class AlbumListScreenViewModel(listRefresher: ListRefresher) : CachedListViewModel(listRefresher, ListRefresher.Target.ALBUMS) {
    // Reads the already-live, process-lifetime cache instead of re-subscribing to libraryRepository.observeAlbums() itself — see
    // AppLibraryCache's own doc for why a fresh per-visit stateIn() here was the actual root cause of Albums' reported per-visit load delay.
    val filter = ListFilter(viewModelScope)
    val albums = filter.narrowSplit(AppAvailability.albums.value) { album, query ->
        album.name.containsIgnoringCase(query) || album.artistName.containsIgnoringCase(query)
    }
}

class AlbumListScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, AlbumListScreenViewModel>(activity) {

    override val viewModelClass = AlbumListScreenViewModel::class.java

    override fun createViewModel() = AlbumListScreenViewModel(AppGraph.from(lightContext).listRefresher)

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val filter by viewModel.filter.query.collectAsState()
        val albumActions = rememberAlbumActions(activity, lightContext)

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Albums"),
                    rightButton = filterButton("albums", filter, viewModel.filter::set),
                )
            },
        ) {
            if (albums.isEmpty) EmptyListNote("albums", filter)
            ScreenList(viewModel.scrollPosition) {
                availableItems(albums, key = { it.id }) { album, unavailable ->
                    AlbumRow(
                        lightContext = lightContext,
                        album = album,
                        secondLine = album.artistLine,
                        unavailable = unavailable,
                        onClick = { navigateTo({ a -> AlbumDetailScreen(a, album.id, album) }) },
                        onOpenActions = { albumActions.openMenu(album.id, album.name, album.isFavorite) },
                    )
                }
            }
        }
    }
}
