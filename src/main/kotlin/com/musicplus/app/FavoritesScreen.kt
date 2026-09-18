package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesScreenViewModel(
    private val libraryRepository: LibraryRepository,
) : LightViewModel<Unit>() {

    private val allArtists: StateFlow<List<Artist>> = libraryRepository.observeFavoriteArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allAlbums: StateFlow<List<Album>> = libraryRepository.observeFavoriteAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allTracks: StateFlow<List<Track>> = libraryRepository.observeFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    val artists: StateFlow<List<Artist>> = combine(allArtists, _filter) { list, q ->
        if (q.isBlank()) list else list.filter { it.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = combine(allAlbums, _filter) { list, q ->
        if (q.isBlank()) list else list.filter { it.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<Track>> = combine(allTracks, _filter) { list, q ->
        if (q.isBlank()) list else list.filter { it.title.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // No-op — favorites are derived from the local `starred` cache column, kept
        // current by the various refresh*() calls elsewhere and by setXFavorite's own
        // optimistic write. LibraryRepository has no separate "refresh favorites" call.
    }

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    var scrollIndex = 0
    var scrollOffset = 0
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
        val filter by viewModel.filter.collectAsState()

        MusicPlusScaffold(
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
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            val listState = rememberPersistedLazyListState(viewModel.scrollIndex, viewModel.scrollOffset) { i, o ->
                viewModel.scrollIndex = i
                viewModel.scrollOffset = o
            }
            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), listState = listState, uniformItemHeightGridUnits = 3f) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    FavoriteRow(artist.name) {
                        navigateTo({ a -> ArtistDetailScreen(a, artist.id) })
                    }
                }
                item { SectionHeader("Albums") }
                items(albums, key = { "album-${it.id}" }) { album ->
                    FavoriteRowWithArt(lightContext, album.name, album.coverArtUrl) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id, album) })
                    }
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    FavoriteTrackRow(
                        track = track,
                        onPlay = {
                            // beginPlay() + navigate immediately, *then* the slow
                            // part — see PlaybackRepository.beginPlay's doc.
                            val graph = AppGraph.from(lightContext)
                            val playback = PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir)
                            playback.beginPlay(listOf(track), 0)
                            navigateTo(::PlayerScreen)
                            scope.launch { playback.play(listOf(track), 0) }
                        },
                        onOpenActions = {
                            navigateTo({ a ->
                                lateinit var addToQueueItem: ActionMenuItem
                                addToQueueItem = ActionMenuItem(
                                    icon = LightIcons.ADD,
                                    label = "Add to queue",
                                    onSelect = ActionMenuSelection.Perform {
                                        val graph = AppGraph.from(lightContext)
                                        PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).addToQueue(listOf(track))
                                        addToQueueItem
                                    },
                                )
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = track.title,
                                    items = listOf(
                                        addToQueueItem,
                                        ActionMenuItem(
                                            icon = LightIcons.LIST,
                                            label = "Add to playlist",
                                            onSelect = ActionMenuSelection.Navigate {
                                                navigateTo({ a2 -> PlaylistPickerScreen(a2, track.id) })
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

/** Tap to play (unchanged); long-press for the action menu (add to queue, add to playlist — issue #16). */
@Composable
private fun FavoriteTrackRow(
    track: Track,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onPlay, onLongClick = onOpenActions)
            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Favorite album rows — the ones with cover art (see issue #9 scope; artist rows stay [FavoriteRow]). */
@Composable
private fun FavoriteRowWithArt(lightContext: SealedLightContext, label: String, coverArtUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
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
