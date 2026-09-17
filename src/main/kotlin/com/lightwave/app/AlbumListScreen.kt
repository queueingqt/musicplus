package com.lightwave.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.LibraryRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumListScreenViewModel(
    private val libraryRepository: LibraryRepository,
) : LightViewModel<Unit>() {

    private val allAlbums: StateFlow<List<Album>> = libraryRepository.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    // Client-side filter over the already-cached list — a search-within-this-screen
    // affordance, distinct in purpose from SearchScreen's server-side search3 call
    // across all categories.
    val albums: StateFlow<List<Album>> = combine(allAlbums, _filter) { albums, query ->
        if (query.isBlank()) albums
        else albums.filter { it.name.contains(query, ignoreCase = true) || it.artistName?.contains(query, ignoreCase = true) == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshAlbumList() }
    }
}

class AlbumListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, AlbumListScreenViewModel>(activity) {

    override val viewModelClass = AlbumListScreenViewModel::class.java

    override fun createViewModel() = AlbumListScreenViewModel(AppGraph.from(lightContext).libraryRepository)

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val filter by viewModel.filter.collectAsState()

        LightwaveTheme {
        Column {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), center = LightTopBarCenter.Text("Albums"))
            LightTextField(
                label = "Search",
                value = filter,
                placeholder = "Filter albums",
                onClick = {
                    navigateTo({ a -> TextEditScreen(a, "Search albums", filter) }) { result ->
                        viewModel.setFilter(result)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
            )
            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                items(albums, key = { it.id }) { album ->
                    AlbumRow(lightContext, album) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id) })
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun AlbumRow(lightContext: SealedLightContext, album: Album, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            lightContext = lightContext,
            url = album.coverArtUrl,
            size = 2.5f.gridUnitsAsDp(),
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        Column {
            LightText(text = album.name, variant = LightTextVariant.Copy)
            LightText(text = album.artistName ?: "Unknown artist", variant = LightTextVariant.Fine)
        }
    }
}
