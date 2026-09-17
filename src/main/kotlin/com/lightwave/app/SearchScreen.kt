package com.lightwave.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchScreenViewModel(
    private val libraryRepository: LibraryRepository,
) : LightViewModel<Unit>() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _artistResults = MutableStateFlow<List<Artist>>(emptyList())
    val artistResults: StateFlow<List<Artist>> = _artistResults.asStateFlow()

    private val _albumResults = MutableStateFlow<List<Album>>(emptyList())
    val albumResults: StateFlow<List<Album>> = _albumResults.asStateFlow()

    private val _trackResults = MutableStateFlow<List<Track>>(emptyList())
    val trackResults: StateFlow<List<Track>> = _trackResults.asStateFlow()

    // Search-on-submit, not search-as-you-type: the real LightOS text entry flow
    // (LightTextInputEditor, a dedicated full-screen editor reached via
    // TextEditScreen) only hands back a value when the user submits, not on every
    // keystroke — which also matches the platform's generally deliberate,
    // one-thing-at-a-time interaction style rather than being a compromise.
    fun runSearch(newQuery: String) {
        _query.value = newQuery
        viewModelScope.launch {
            if (newQuery.isBlank()) {
                _artistResults.value = emptyList()
                _albumResults.value = emptyList()
                _trackResults.value = emptyList()
            } else {
                val (artists, albums, tracks) = libraryRepository.search(newQuery)
                _artistResults.value = artists
                _albumResults.value = albums
                _trackResults.value = tracks
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // No-op — results are driven by runSearch(), not screen-show.
    }
}

class SearchScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, SearchScreenViewModel>(activity) {

    override val viewModelClass = SearchScreenViewModel::class.java

    override fun createViewModel() = SearchScreenViewModel(AppGraph.from(lightContext).libraryRepository)

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val query by viewModel.query.collectAsState()
        val artists by viewModel.artistResults.collectAsState()
        val albums by viewModel.albumResults.collectAsState()
        val tracks by viewModel.trackResults.collectAsState()

        LightwaveTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), center = LightTopBarCenter.Text("Search"))

            LightTextField(
                label = "Search",
                value = query,
                placeholder = "Artists, albums, tracks",
                onClick = {
                    navigateTo({ a -> TextEditScreen(a, "Search", query) }) { result ->
                        viewModel.runSearch(result)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            )

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    ResultRow(artist.name) {
                        navigateTo({ a -> ArtistDetailScreen(a, artist.id) })
                    }
                }
                item { SectionHeader("Albums") }
                items(albums, key = { "album-${it.id}" }) { album ->
                    ResultRow(album.name) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id) })
                    }
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackResultRow(
                        label = track.title,
                        onClick = {
                            scope.launch {
                                val graph = AppGraph.from(lightContext)
                                PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).play(listOf(track), 0)
                                navigateTo(::PlayerScreen)
                            }
                        },
                        onAddToPlaylist = { navigateTo({ a -> PlaylistPickerScreen(a, track.id) }) },
                    )
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
private fun ResultRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    )
}

@Composable
private fun TrackResultRow(label: String, onClick: () -> Unit, onAddToPlaylist: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onClick),
        )
        LightIcon(
            icon = LightIcons.ADD,
            size = 1.5f,
            contentDescription = "Add to playlist",
            modifier = Modifier
                .lightClickable(onClick = onAddToPlaylist)
                .padding(start = 0.5f.gridUnitsAsDp()),
        )
    }
}
