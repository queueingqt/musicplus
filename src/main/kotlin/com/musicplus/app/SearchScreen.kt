package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.LibraryRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
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
    //
    // Results stream in: what is cached shows at once, then each server's live answer replaces its
    // cached slice as it arrives (see LibraryRepository.searchStream). A new search cancels the last.
    private var searchJob: Job? = null

    fun runSearch(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            libraryRepository.searchStream(newQuery).collect { results ->
                _artistResults.value = results.artists
                _albumResults.value = results.albums
                _trackResults.value = results.tracks
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // No-op — results are driven by runSearch(), not screen-show.
    }

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()

    // Gates the auto-open LaunchedEffect in Content() below to firing only
    // once per ViewModel lifetime (i.e. only the first time this screen is
    // ever shown), not once per Content() composition. Reported live: pushing
    // TextEditScreen on top of SearchScreen hides it, and per ScrollPosition.kt's
    // documented navigation behavior, Content() is fully disposed and
    // recomposed from scratch on every hide/show — so both backing out of the
    // editor AND submitting a search popped back to a freshly recomposed
    // Content(), whose LaunchedEffect(Unit) fired again and immediately
    // reopened the editor, making the screen look stuck in a loop.
    var hasAutoOpenedEditor = false
}

class SearchScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, SearchScreenViewModel>(activity) {

    override val viewModelClass = SearchScreenViewModel::class.java

    override fun createViewModel(): SearchScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return SearchScreenViewModel(graph.libraryRepository)
    }

    @Composable
    override fun Content() {
        val query by viewModel.query.collectAsState()
        val artists by viewModel.artistResults.collectAsState()
        val albums by viewModel.albumResults.collectAsState()
        val tracks by viewModel.trackResults.collectAsState()
        val trackActions = rememberTrackActions(activity, lightContext)

        // Both snapshotted once per show (not re-read after). hadAlreadyOpened
        // answers "was the editor already auto-opened before *this* appearance
        // of the screen" — false only on the very first-ever show. leavingBlank
        // answers "are we returning from that editor (or a later reopen) with
        // no query ever having been run" — TextEditScreen's back button cancels
        // without invoking the result callback (see its doc), so a blank query
        // here means nothing was ever searched. Both cases skip the body below:
        // the first because the editor is about to cover it, the second because
        // LaunchedEffect is about to leave Search entirely — neither should get
        // a frame to flash before that happens (reported live for both).
        val hadAlreadyOpened = remember { viewModel.hasAutoOpenedEditor }
        val leavingBlank = remember { hadAlreadyOpened && viewModel.query.value.isBlank() }

        // Open the text editor immediately on arrival, not just on a tap — this
        // screen exists to be typed into right away, and requiring an extra tap
        // on the field first was a real friction point reported live. Gated on
        // the ViewModel-backed hasAutoOpenedEditor (see its doc) rather than
        // just LaunchedEffect(Unit), since Content() itself is recomposed from
        // scratch every time TextEditScreen is pushed/popped on top of this
        // screen — the field's own onClick below still covers wanting to
        // search again afterward.
        LaunchedEffect(Unit) {
            if (!hadAlreadyOpened) {
                viewModel.hasAutoOpenedEditor = true
                navigateTo({ a -> TextEditScreen(a, "Search", query) }) { result ->
                    viewModel.runSearch(result)
                }
            } else if (leavingBlank) {
                // Leave Search entirely instead of landing on this empty screen
                // and making the person press back a second time.
                goBack()
            }
        }

        if (!hadAlreadyOpened || leavingBlank) return

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Search"),
                )
            },
        ) {
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

            val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
            // Inside, not the default Outside — see ScrollbarGutter.kt's doc
            // (issue #39): ResultRowWithArt's/TrackRow's maxLines=1/
            // Ellipsis titles are width-dependent, so on Outside they briefly
            // rendered wider (less truncated) on the first frame, then
            // visibly snapped narrower once the scrollbar's real gutter was
            // reserved.
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 3f,
            ) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    ResultRow(artist.nameLine) {
                        navigateTo({ a -> ArtistDetailScreen(a, artist.id) })
                    }
                }
                item { SectionHeader("Albums") }
                items(albums, key = { "album-${it.id}" }) { album ->
                    ResultRowWithArt(lightContext, album.nameLine, album.coverArtId) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id, album) })
                    }
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        subtitle = track.serverLabel,
                        leading = {
                            AlbumArt(
                                lightContext = lightContext,
                                coverArtId = track.coverArtId,
                                size = 2.5f.gridUnitsAsDp(),
                                modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
                            )
                        },
                        onPlay = { trackActions.play(listOf(track)) },
                        onOpenActions = { trackActions.openMenu(track) },
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
private fun ResultRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        // end matches the SDK's own scrollbar track width — see the
        // LightLazyScrollView call site above for why this is fixed rather
        // than conditional on whether a scrollbar happens to show.
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
    )
}

/** Album/track results — the ones with cover art (see issue #9 scope; artist results stay [ResultRow]). */
@Composable
private fun ResultRowWithArt(lightContext: SealedLightContext, label: String, coverArtId: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            lightContext = lightContext,
            coverArtId = coverArtId,
            size = 2.5f.gridUnitsAsDp(),
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// Track rows moved to the shared TrackRow.kt module — see its doc.
