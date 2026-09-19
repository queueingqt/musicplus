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
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistListScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
) : LightViewModel<Unit>() {

    // See AppLibraryCache's doc — reads the already-live, process-lifetime
    // cache instead of re-subscribing to libraryRepository.observeArtists()
    // on every fresh per-visit ViewModel.
    private val allArtists: StateFlow<List<Artist>> = AppLibraryCache.artists.value

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    val artists: StateFlow<List<Artist>> = filteredBy(allArtists, _filter) { artist, query ->
        artist.name.contains(query, ignoreCase = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    suspend fun setArtistFavorite(id: String, favorite: Boolean) = syncQueueRepository.setArtistFavorite(id, favorite)

    /** See [downloadEntireArtist] (TrackListDownload.kt) — moved here from a standalone row on ArtistDetailScreen itself (reported live, 2026-09-18). */
    fun downloadEntireArtist(lightContext: SealedLightContext, artistId: String) {
        viewModelScope.launch { downloadEntireArtist(lightContext, libraryRepository, downloadRepository, artistId) }
    }

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

class ArtistListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, ArtistListScreenViewModel>(activity) {

    override val viewModelClass = ArtistListScreenViewModel::class.java

    override fun createViewModel(): ArtistListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return ArtistListScreenViewModel(graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository)
    }

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val filter by viewModel.filter.collectAsState()

        MusicPlusScaffold(
            screen = this,
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
        ) {
            val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
            // Inside, not the default Outside — see ScrollbarGutter.kt's doc
            // (issue #39): ArtistRow's trailing favorite star only reserves a
            // stable position when the scrollbar's own gutter isn't racing
            // the LazyColumn's first layout pass. Reported live as a visible
            // flash from full-width to gutter-reserved-width on first load.
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 3f,
            ) {
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
                                        // Moved here from a standalone row on
                                        // ArtistDetailScreen itself (reported
                                        // live, 2026-09-18: that screen was
                                        // too crowded) — no aggregate
                                        // download-state indicator, same
                                        // reasoning as before the move (see
                                        // downloadEntireArtist's own doc,
                                        // TrackListDownload.kt).
                                        confirmActionItem(
                                            icon = LightIcons.DOWNLOAD_ARROW,
                                            label = "Download entire artist",
                                            confirmTitle = "Download entire artist?",
                                            confirmMessage = "Downloads every track across ${artist.albumCount} " +
                                                (if (artist.albumCount == 1) "album" else "albums") + " to this device.",
                                            confirmContentDescription = "Download entire artist",
                                            onConfirm = { viewModel.downloadEntireArtist(lightContext, artist.id) },
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
private fun ArtistRow(artist: Artist, onClick: () -> Unit, onOpenActions: () -> Unit) {
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
