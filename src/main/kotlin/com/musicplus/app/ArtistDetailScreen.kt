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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ArtistDetailScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val artistId: String,
) : ListScreenViewModel() {

    // Reported live, 2026-09-18: the search field above this list took up
    // too much space on a screen that's already grown (similar artists/top
    // songs sections, the album list itself) and wasn't worth it — removed
    // rather than kept as dead weight. No filtering here anymore; every
    // album always shows.
    val albums: StateFlow<List<Album>> = libraryRepository.observeAlbumsByArtist(artistId)
        .screenState(viewModelScope, emptyList())

    // Same caveat as AlbumDetailScreenViewModel's `album` state: depends on the
    // artist already being cached locally (true once refreshArtists() has run, e.g.
    // from ArtistListScreen or HomeScreen) — there's no observeArtistById.
    val artist: StateFlow<Artist?> = libraryRepository.observeArtists()
        .map { artists -> artists.find { it.id == artistId } }
        .screenState(viewModelScope, null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshArtistDetail(artistId) }
    }

    fun toggleFavorite() {
        val isFavorite = artist.value?.isFavorite ?: false
        viewModelScope.launch { syncQueueRepository.setArtistFavorite(artistId, !isFavorite) }
    }

    // Similar artists / top songs — see LazyCollapsibleSection's own doc:
    // both collapsed by default, fetched only on first expand (not eagerly
    // in onScreenShow like albums/artist above).
    val similarArtistsSection = LazyCollapsibleSection(viewModelScope) { libraryRepository.getSimilarArtists(artistId) }

    // getTopSongs.view is keyed by the artist's *name*, not id (see
    // SubsonicApi.getTopSongs's doc) — read from the already-observed
    // `artist` StateFlow rather than a second network round trip just to
    // resolve the name.
    val topSongsSection = LazyCollapsibleSection(viewModelScope) {
        val name = artist.value?.name
        if (name.isNullOrBlank()) emptyList() else libraryRepository.getTopSongs(artistId, name)
    }
}

class ArtistDetailScreen(
    private val activity: SealedLightActivity,
    private val artistId: String,
) : LightScreen<Unit, ArtistDetailScreenViewModel>(activity) {

    override val viewModelClass = ArtistDetailScreenViewModel::class.java

    override fun createViewModel(): ArtistDetailScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return ArtistDetailScreenViewModel(graph.libraryRepository, graph.syncQueueRepository, artistId)
    }

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val artist by viewModel.artist.collectAsState()
        val similarArtistsExpanded by viewModel.similarArtistsSection.expanded.collectAsState()
        val similarArtistsLoading by viewModel.similarArtistsSection.loading.collectAsState()
        val similarArtists by viewModel.similarArtistsSection.items.collectAsState()
        val topSongsExpanded by viewModel.topSongsSection.expanded.collectAsState()
        val topSongsLoading by viewModel.topSongsSection.loading.collectAsState()
        val topSongs by viewModel.topSongsSection.items.collectAsState()
        val trackActions = rememberTrackActions(activity, lightContext)
        val albumActions = rememberAlbumActions(activity, lightContext)

        // Favorite inline with the artist name — via LightTopBar's rightButton
        // slot, rather than a separate row, since the name is already the title
        // here (no need to repeat it). Icon-only, no text label.
        MusicPlusScaffold(
            screen = this,
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
        ) {
            // Everything below — both collapsible sections' rows and the
            // album list — lives in ONE LightLazyScrollView rather than a
            // plain Column wrapping a separate nested lazy list.
            // LightLazyScrollView's own LazyColumn
            // measures itself against the full available height regardless of
            // what precedes it (it's a plain `Box { LazyColumn(Modifier.fillMaxSize()) }`
            // internally, no weight-based reduction — confirmed by reading the
            // SDK source), so a plain Column with fixed content above it only
            // stays safe as long as that fixed content is small. "Similar
            // artists"/"Top songs" can each expand to their own 20-row cap —
            // enough fixed content, stacked outside any scroll container, to
            // push the entire album list (and the tail of the expanded
            // sections themselves) off-screen with no way to scroll back up to
            // it. Folding everything into this one lazy list's own items
            // instead means the whole screen scrolls together, same as
            // expanding a section on any real "list with header content" UI.
            // uniformItemHeightGridUnits stays 3f (this component's own scroll-
            // bar-thumb math only, per its doc — the real LazyColumn still
            // measures each item's actual height correctly regardless), so the
            // one cost is a slightly-imprecise scrollbar thumb size/position
            // once heterogeneous-height header rows are mixed with the
            // uniform-height album rows — cosmetic only, not a scrolling bug.
            ScreenList(viewModel.scrollPosition) {
                // "About this artist" content — collapsed sections, fetched
                // only once actually expanded (see the ViewModel's
                // toggleSimilarArtists/toggleTopSongs doc).
                item {
                    CollapsibleSectionHeader(
                        title = "Similar artists",
                        expanded = similarArtistsExpanded,
                        onClick = { viewModel.similarArtistsSection.toggle() },
                    )
                }
                if (similarArtistsExpanded) {
                    when {
                        similarArtistsLoading -> item { SectionStatusText("Loading…") }
                        similarArtists.isEmpty() -> item { SectionStatusText("No similar artists found") }
                        else -> items(similarArtists, key = { "similar-${it.id}" }) { similarArtist ->
                            SimilarArtistRow(
                                artist = similarArtist,
                                onClick = { navigateTo({ a -> ArtistDetailScreen(a, similarArtist.id) }) },
                            )
                        }
                    }
                }

                item {
                    CollapsibleSectionHeader(
                        title = "Top songs",
                        expanded = topSongsExpanded,
                        onClick = { viewModel.topSongsSection.toggle() },
                    )
                }
                if (topSongsExpanded) {
                    when {
                        topSongsLoading -> item { SectionStatusText("Loading…") }
                        topSongs.isEmpty() -> item { SectionStatusText("No top songs found") }
                        else -> items(topSongs, key = { "top-${it.id}" }) { track ->
                            TrackRow(
                                track = track,
                                subtitle = track.artistName ?: "Unknown artist",
                                onPlay = { trackActions.play(listOf(track)) },
                                onOpenActions = { trackActions.openMenu(track) },
                            )
                        }
                    }
                }

                items(albums, key = { it.id }) { album ->
                    AlbumRow(
                        lightContext = lightContext,
                        album = album,
                        // The artist is the same for every row here, so the second line is how many songs the album has.
                        secondLine = "${album.songCount} tracks",
                        onClick = { navigateTo({ a -> AlbumDetailScreen(a, album.id, album) }) },
                        onOpenActions = { albumActions.openMenu(album.id, album.name, album.isFavorite) },
                    )
                }
            }
        }
    }
}

/** Feature: similar artists / top songs — shared tappable header for both collapsible sections. No existing expand/collapse pattern elsewhere in this codebase (checked) — UP/DOWN chosen to match the icon vocabulary QueueScreen's reorder rows already use, rather than introducing a new glyph concept. */
@Composable
private fun CollapsibleSectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = title, variant = LightTextVariant.Heading, modifier = Modifier.weight(1f))
        // Standard disclosure-triangle convention (collapsed points at its
        // content, expanded points down at what's now revealed below it).
        // DOWN, not ARROW_DOWN — checked both drawables directly: ARROW_DOWN
        // is an arrow-with-stem, the same shape family as DOWNLOAD_ARROW
        // (already a loaded glyph in this app, used for the real download
        // rows), and reported live (2026-09-18) as reading like a download
        // button once expanded. DOWN/ARROW_RIGHT share the exact same plain
        // chevron path (just rotated/mirrored — confirmed in the SDK's own
        // vector XML), a properly matched pair with no such collision. Also
        // not UP/DOWN as a pair — UP already means "reorder" elsewhere in
        // this app (QueueScreen's move-up rows), and DOWN for the collapsed
        // state read as "already open" (reported live, same session).
        LightIcon(
            icon = if (expanded) LightIcons.DOWN else LightIcons.ARROW_RIGHT,
            size = 1.5f,
            contentDescription = if (expanded) "Collapse" else "Expand",
        )
    }
}

/** Loading/empty placeholder inside an expanded section — same shape as SongsListScreen's "No songs yet" empty state. */
@Composable
private fun SectionStatusText(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** A similar artist — tap navigates to that artist's own ArtistDetailScreen, same call shape as ArtistListScreen/SearchScreen's identical row. */
@Composable
private fun SimilarArtistRow(artist: Artist, onClick: () -> Unit) {
    LightText(
        text = artist.name,
        variant = LightTextVariant.Copy,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(top = 0.75f.gridUnitsAsDp(), bottom = 0.75f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
    )
}
