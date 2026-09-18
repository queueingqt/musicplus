package com.musicplus.app

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistListScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val syncQueueRepository: SyncQueueRepository,
) : LightViewModel<Unit>() {

    private val allArtists: StateFlow<List<Artist>> = libraryRepository.observeArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    val artists: StateFlow<List<Artist>> = combine(allArtists, _filter) { artists, query ->
        if (query.isBlank()) artists else artists.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshArtists() }
    }

    suspend fun setArtistFavorite(id: String, favorite: Boolean) = syncQueueRepository.setArtistFavorite(id, favorite)

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    var scrollIndex = 0
    var scrollOffset = 0
}

class ArtistListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, ArtistListScreenViewModel>(activity) {

    override val viewModelClass = ArtistListScreenViewModel::class.java

    override fun createViewModel(): ArtistListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return ArtistListScreenViewModel(graph.libraryRepository, graph.syncQueueRepository)
    }

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val filter by viewModel.filter.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Artists"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = {
                            navigateTo({ a -> TextEditScreen(a, "Search artists", filter) }) { result ->
                                viewModel.setFilter(result)
                            }
                        },
                        contentDescription = "Search artists",
                    ),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            val listState = rememberPersistedLazyListState(viewModel.scrollIndex, viewModel.scrollOffset) { i, o ->
                viewModel.scrollIndex = i
                viewModel.scrollOffset = o
            }
            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), listState = listState, uniformItemHeightGridUnits = 3f) {
                items(artists, key = { it.id }) { artist ->
                    ArtistRow(
                        artist = artist,
                        onClick = { navigateTo({ a -> ArtistDetailScreen(a, artist.id) }) },
                        onOpenActions = {
                            navigateTo({ a ->
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = artist.name,
                                    items = listOf(
                                        favoriteActionItem(artist.isFavorite) { favorite ->
                                            viewModel.setArtistFavorite(artist.id, favorite)
                                        },
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
private fun ArtistRow(artist: Artist, onClick: () -> Unit, onOpenActions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = artist.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = "${artist.albumCount} albums", variant = LightTextVariant.Fine)
        }
        // Matches AlbumRow's identical treatment — no other at-a-glance signal
        // an artist is favorited exists on this list.
        if (artist.isFavorite) {
            LightIcon(
                icon = LightIcons.STAR,
                size = 1.2f,
                contentDescription = "Favorited",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
