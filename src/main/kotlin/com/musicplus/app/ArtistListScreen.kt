package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppAvailability
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

class ArtistListScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
    listRefresher: ListRefresher,
) : CachedListViewModel(listRefresher, ListRefresher.Target.ARTISTS) {

    // See AppLibraryCache's doc — reads the already-live, process-lifetime cache instead of re-subscribing to observeArtists() on every
    // fresh per-visit ViewModel.
    val filter = ListFilter(viewModelScope)
    val artists = filter.narrowSplit(AppAvailability.artists.value) { artist, query -> artist.name.containsIgnoringCase(query) }

    suspend fun setArtistFavorite(id: String, favorite: Boolean) = syncQueueRepository.setArtistFavorite(id, favorite)

    /** See [downloadEntireArtist] (TrackListDownload.kt) — moved here from a standalone row on ArtistDetailScreen itself (reported live, 2026-09-18). */
    fun downloadEntireArtist(lightContext: SealedLightContext, artistId: String) {
        viewModelScope.launch { downloadEntireArtist(lightContext, libraryRepository, downloadRepository, artistId) }
    }
}

class ArtistListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, ArtistListScreenViewModel>(activity) {

    override val viewModelClass = ArtistListScreenViewModel::class.java

    override fun createViewModel(): ArtistListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return ArtistListScreenViewModel(graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository, graph.listRefresher)
    }

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val filter by viewModel.filter.query.collectAsState()

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Artists"),
                    rightButton = filterButton("artists", filter, viewModel.filter::set),
                )
            },
        ) {
            if (artists.isEmpty) EmptyListNote("artists", filter)
            ScreenList(viewModel.scrollPosition) {
                availableItems(artists, key = { it.id }) { artist, unavailable ->
                    ArtistRow(
                        artist = artist,
                        unavailable = unavailable,
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
                                        // No aggregate download-state indicator: see downloadEntireArtist's own doc (TrackListDownload.kt).
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
private fun ArtistRow(artist: Artist, unavailable: Boolean, onClick: () -> Unit, onOpenActions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fadedWhen(unavailable)
            .lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions)
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = artist.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = artist.albumsLine, variant = LightTextVariant.Fine)
        }
        if (artist.isFavorite) FavoritedStar()
    }
}
