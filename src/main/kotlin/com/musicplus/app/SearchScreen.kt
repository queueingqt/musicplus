package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    var scrollIndex = 0
    var scrollOffset = 0
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

        // Open the text editor immediately on arrival, not just on a tap — this
        // screen exists to be typed into right away, and requiring an extra tap
        // on the field first was a real friction point reported live. Runs once
        // per SearchScreen instance (LaunchedEffect(Unit) survives recomposition,
        // not screen navigation), so backing out of the editor without submitting
        // doesn't re-trigger it — the field's own onClick below still covers
        // wanting to search again afterward.
        LaunchedEffect(Unit) {
            navigateTo({ a -> TextEditScreen(a, "Search", query) }) { result ->
                viewModel.runSearch(result)
            }
        }

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Search"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
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

            val listState = rememberPersistedLazyListState(viewModel.scrollIndex, viewModel.scrollOffset) { i, o ->
                viewModel.scrollIndex = i
                viewModel.scrollOffset = o
            }
            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), listState = listState, uniformItemHeightGridUnits = 3f) {
                item { SectionHeader("Artists") }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    ResultRow(artist.name) {
                        navigateTo({ a -> ArtistDetailScreen(a, artist.id) })
                    }
                }
                item { SectionHeader("Albums") }
                items(albums, key = { "album-${it.id}" }) { album ->
                    ResultRowWithArt(lightContext, album.name, album.coverArtUrl) {
                        navigateTo({ a -> AlbumDetailScreen(a, album.id, album) })
                    }
                }
                item { SectionHeader("Tracks") }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackResultRow(
                        lightContext = lightContext,
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

/** Album/track results — the ones with cover art (see issue #9 scope; artist results stay [ResultRow]). */
@Composable
private fun ResultRowWithArt(lightContext: SealedLightContext, label: String, coverArtUrl: String?, onClick: () -> Unit) {
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

/** Tap to play (unchanged); long-press for the action menu (add to queue, add to playlist — issue #16). */
@Composable
private fun TrackResultRow(
    lightContext: SealedLightContext,
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
        AlbumArt(
            lightContext = lightContext,
            url = track.coverArtUrl,
            size = 2.5f.gridUnitsAsDp(),
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
