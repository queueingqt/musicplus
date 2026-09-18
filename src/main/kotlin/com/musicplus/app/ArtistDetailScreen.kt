package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistDetailScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val artistId: String,
) : LightViewModel<Unit>() {

    private val allAlbums: StateFlow<List<Album>> = libraryRepository.observeAlbumsByArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    val albums: StateFlow<List<Album>> = combine(allAlbums, _filter) { albums, query ->
        if (query.isBlank()) albums else albums.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

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
        viewModelScope.launch { syncQueueRepository.setArtistFavorite(artistId, !isFavorite) }
    }

    fun albumDownloadState(albumId: String): Flow<AlbumDownloadState> =
        observeAlbumDownloadState(libraryRepository, downloadRepository, albumId)

    suspend fun toggleAlbumDownload(lightContext: SealedLightContext, albumId: String): AlbumDownloadState =
        toggleAlbumDownload(lightContext, libraryRepository, downloadRepository, albumId)

    suspend fun setAlbumFavorite(id: String, favorite: Boolean) = syncQueueRepository.setAlbumFavorite(id, favorite)

    suspend fun tracksForAlbum(albumId: String): List<Track> = libraryRepository.observeTracksByAlbum(albumId).first()

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    var scrollIndex = 0
    var scrollOffset = 0
}

class ArtistDetailScreen(
    private val activity: SealedLightActivity,
    private val artistId: String,
) : LightScreen<Unit, ArtistDetailScreenViewModel>(activity) {

    override val viewModelClass = ArtistDetailScreenViewModel::class.java

    override fun createViewModel(): ArtistDetailScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return ArtistDetailScreenViewModel(graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository, artistId)
    }

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val artist by viewModel.artist.collectAsState()
        val filter by viewModel.filter.collectAsState()

        // Favorite inline with the artist name — via LightTopBar's rightButton
        // slot, rather than a separate row, since the name is already the title
        // here (no need to repeat it). Icon-only, no text label.
        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(artist?.name ?: "Artist"),
                    rightButton = LightBarButton.LightIcon(
                        icon = if (artist?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        onClick = { viewModel.toggleFavorite() },
                        contentDescription = if (artist?.isFavorite == true) "Favorited" else "Favorite",
                    ),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
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

            // Inside, not Outside — see AlbumDetailScreen's identical call site
            // for why (Outside's gutter width isn't known until after first
            // layout, so the trailing favorite star briefly rendered full-width
            // then jumped left once it appeared; AlbumRow reserves the same
            // width itself, unconditionally, instead).
            val listState = rememberPersistedLazyListState(viewModel.scrollIndex, viewModel.scrollOffset) { i, o ->
                viewModel.scrollIndex = i
                viewModel.scrollOffset = o
            }
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 3f,
            ) {
                items(albums, key = { it.id }) { album ->
                    val downloadState by remember(album.id) { viewModel.albumDownloadState(album.id) }
                        .collectAsState(initial = AlbumDownloadState.NONE)
                    AlbumRow(
                        lightContext = lightContext,
                        album = album,
                        onClick = { navigateTo({ a -> AlbumDetailScreen(a, album.id, album) }) },
                        onOpenActions = {
                            navigateTo({ a ->
                                lateinit var addToQueueItem: ActionMenuItem
                                addToQueueItem = ActionMenuItem(
                                    icon = LightIcons.ADD,
                                    label = "Add album to queue",
                                    onSelect = ActionMenuSelection.Perform {
                                        val tracks = viewModel.tracksForAlbum(album.id)
                                        val graph = AppGraph.from(lightContext)
                                        PlaybackRepositoryHolder.get(activity, graph, lightContext.filesDir).addToQueue(tracks)
                                        addToQueueItem
                                    },
                                )
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = album.name,
                                    items = listOf(
                                        favoriteActionItem(album.isFavorite) { favorite ->
                                            viewModel.setAlbumFavorite(album.id, favorite)
                                        },
                                        albumDownloadActionItem(downloadState) { viewModel.toggleAlbumDownload(lightContext, album.id) }.copy(
                                            liveUpdates = viewModel.albumDownloadState(album.id).map { s ->
                                                albumDownloadActionItem(s) { viewModel.toggleAlbumDownload(lightContext, album.id) }
                                            },
                                        ),
                                        addToQueueItem,
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
private fun AlbumRow(
    lightContext: SealedLightContext,
    album: Album,
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
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            lightContext = lightContext,
            url = album.coverArtUrl,
            size = 2.5f.gridUnitsAsDp(),
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = album.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = "${album.songCount} tracks", variant = LightTextVariant.Fine)
        }
        // Reported live: no way to tell an album was favorited from this list.
        if (album.isFavorite) {
            LightIcon(
                icon = LightIcons.STAR,
                size = 1.2f,
                contentDescription = "Favorited",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
