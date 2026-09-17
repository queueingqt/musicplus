package com.lightwave.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.LibraryRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistDetailScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val artistId: String,
) : LightViewModel<Unit>() {

    val albums: StateFlow<List<Album>> = libraryRepository.observeAlbumsByArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Same caveat as AlbumDetailScreenViewModel's `album` state: depends on the
    // artist already being cached locally (true once refreshArtists() has run, e.g.
    // from ArtistListScreen or HomeScreen) — there's no observeArtistById.
    val artist: StateFlow<Artist?> = libraryRepository.observeArtists()
        .map { artists -> artists.find { it.id == artistId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshArtistDetail(artistId) }
    }

    fun toggleFavorite() {
        val isFavorite = artist.value?.isFavorite ?: false
        viewModelScope.launch { libraryRepository.setArtistFavorite(artistId, !isFavorite) }
    }
}

class ArtistDetailScreen(
    activity: SealedLightActivity,
    private val artistId: String,
) : LightScreen<Unit, ArtistDetailScreenViewModel>(activity) {

    override val viewModelClass = ArtistDetailScreenViewModel::class.java

    override fun createViewModel() =
        ArtistDetailScreenViewModel(AppGraph.from(lightContext).libraryRepository, artistId)

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val artist by viewModel.artist.collectAsState()

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(center = LightTopBarCenter.Text(artist?.name ?: "Artist"))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { viewModel.toggleFavorite() }
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            ) {
                LightIcon(icon = if (artist?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE, size = 1.5f)
                LightText(
                    text = if (artist?.isFavorite == true) "Favorited" else "Favorite",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                )
            }

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                items(albums, key = { it.id }) { album ->
                    AlbumRow(album) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = album.name, variant = LightTextVariant.Copy)
        LightText(text = "${album.songCount} tracks", variant = LightTextVariant.Fine)
    }
}
