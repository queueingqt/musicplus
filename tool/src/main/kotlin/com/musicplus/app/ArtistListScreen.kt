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
import com.musicplus.app.data.AppState
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ListRefresher
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class ArtistListScreenViewModel(
    listRefresher: ListRefresher,
    appState: AppState,
) : CachedListViewModel(listRefresher, ListRefresher.Target.ARTISTS) {

    // See AppLibraryCache's doc — reads the already-live, process-lifetime cache instead of re-subscribing to observeArtists() on every
    // fresh per-visit ViewModel.
    val filter = ListFilter(viewModelScope)
    val artists = filter.narrowSplit(appState.availability.artists.value) { artist, query -> artist.name.containsIgnoringCase(query) }
}

class ArtistListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, ArtistListScreenViewModel>(activity) {

    override val viewModelClass = ArtistListScreenViewModel::class.java

    override fun createViewModel(): ArtistListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return ArtistListScreenViewModel(graph.listRefresher, graph.appState)
    }

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val filter by viewModel.filter.query.collectAsState()
        val artistActions = rememberArtistActions(lightContext, viewModel.viewModelScope)

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
            if (artists.isEmpty) EmptyListNote("artists", filter, artists.loaded)
            ScreenList(viewModel.scrollPosition) {
                availableItems(artists, key = { it.id }) { artist, unavailable ->
                    ArtistRow(
                        artist = artist,
                        unavailable = unavailable,
                        onClick = { navigateTo({ a -> ArtistDetailScreen(a, artist.id) }) },
                        // No aggregate download-state indicator: see downloadEntireArtist's own doc (DownloadStates.kt).
                        onOpenActions = { artistActions.openMenu(artist.id, artist.name, artist.isFavorite, albumCount = artist.albumCount) },
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
            .listRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = artist.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = artist.albumsLine, variant = LightTextVariant.Fine)
        }
        if (artist.isFavorite) FavoritedStar()
    }
}
