package com.lightwave.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.LibraryRepository
import com.lightwave.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesScreenViewModel(
    private val libraryRepository: LibraryRepository,
) : LightViewModel<Unit>() {

    val artists: StateFlow<List<Artist>> = libraryRepository.observeFavoriteArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = libraryRepository.observeFavoriteAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<Track>> = libraryRepository.observeFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // No-op — favorites are derived from the local `starred` cache column, kept
        // current by the various refresh*() calls elsewhere and by setXFavorite's own
        // optimistic write. LibraryRepository has no separate "refresh favorites" call.
    }
}

class FavoritesScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, FavoritesScreenViewModel>(activity) {

    override val viewModelClass = FavoritesScreenViewModel::class.java

    override fun createViewModel() = FavoritesScreenViewModel(AppGraph.from(lightContext).libraryRepository)

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val artists by viewModel.artists.collectAsState()
        val albums by viewModel.albums.collectAsState()
        val tracks by viewModel.tracks.collectAsState()

        LightwaveTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), center = LightTopBarCenter.Text("Favorites"))
            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    FavoriteRow(artist.name) {
                        navigateTo({ a -> ArtistDetailScreen(a, artist.id) })
                    }
                }
                item { SectionHeader("Albums") }
                items(albums, key = { "album-${it.id}" }) { album ->
                    FavoriteRow(album.name) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id) })
                    }
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    FavoriteRow(track.title) {
                        scope.launch {
                            val graph = AppGraph.from(lightContext)
                            PlaybackRepositoryHolder.get(activity, graph.apiHolder).play(listOf(track), 0)
                            navigateTo(::PlayerScreen)
                        }
                    }
                }
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

@Composable
private fun FavoriteRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    )
}
