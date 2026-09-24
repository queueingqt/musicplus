package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.LibraryRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Flat, unscoped "every song in the library" browse list (previously
 * requested, never actually delivered) — distinct from Favorites' Tracks
 * section (favorited only) and Search (server-side query match only).
 * [LibraryRepository.observeAllTracks]'s local cache is otherwise only ever
 * as complete as whichever albums/playlists/searches happen to have been
 * visited, so [LibraryRepository.refreshAllSongs] — the one real "fetch
 * everything" sync this app does, since Subsonic has no direct getAllSongs
 * endpoint — runs at app start and on reconnect, and again in the background
 * each time this page opens. The page itself never waits for it: it reads
 * the warmed cache (see AppLibraryCache's doc) and the refresh's additions and
 * deletions reach the list through the cache (see ListRefresher's doc).
 */
class SongsListScreenViewModel(
    private val listRefresher: ListRefresher,
) : LightViewModel<Unit>() {

    // The page opens straight from the cache; this re-checks the server in the
    // background and any additions or deletions arrive through the cache
    // itself — no spinner. See ListRefresher's doc.
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        listRefresher.refreshOnOpen(ListRefresher.Target.SONGS)
    }


    // See AppLibraryCache's doc — reads the already-live, process-lifetime
    // cache instead of re-subscribing to libraryRepository.observeAllTracks()
    // on every fresh per-visit ViewModel. This is the collection the warmed-
    // cache fix was originally measured against (several real seconds on a
    // warm process, several thousand tracks) before being generalized to
    // Albums/Artists/Playlists too.
    private val allTracks: StateFlow<List<Track>> = AppLibraryCache.allTracks.value

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter

    // Client-side filter over the already-cached list, same convention as
    // AlbumListScreen/ArtistListScreen — not a server round-trip.
    val tracks: StateFlow<List<Track>> = filteredBy(allTracks, _filter) { track, query ->
        track.title.contains(query, ignoreCase = true) || track.artistName?.contains(query, ignoreCase = true) == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(query: String) {
        _filter.value = query
    }

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

class SongsListScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, SongsListScreenViewModel>(activity) {

    override val viewModelClass = SongsListScreenViewModel::class.java

    override fun createViewModel(): SongsListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return SongsListScreenViewModel(graph.listRefresher)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val filter by viewModel.filter.collectAsState()
        val trackActions = rememberTrackActions(activity, lightContext)

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Songs"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = {
                            navigateTo({ a -> TextEditScreen(a, "Search songs", filter) }) { result ->
                                viewModel.setFilter(result)
                            }
                        },
                        contentDescription = "Search songs",
                    ),
                )
            },
        ) {
            if (tracks.isEmpty()) {
                EmptyListNote("songs", filter)
            } else {
                // Inside, not Outside — see AlbumDetailScreen's identical call
                // site for why (Outside's gutter width isn't known until after
                // first layout, so trailing per-row content briefly renders
                // full-width then jumps left once it appears; SongRow reserves
                // the same width itself, unconditionally, instead).
                val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
                LightLazyScrollView(
                    modifier = Modifier.fillMaxWidth(),
                    scrollBarPosition = LightScrollBarPosition.Inside,
                    listState = listState,
                    uniformItemHeightGridUnits = 3f,
                ) {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            subtitle = track.artistLine,
                            onPlay = { trackActions.play(listOf(track)) },
                            onOpenActions = { trackActions.openMenu(track) },
                        )
                    }
                }
            }
        }
    }
}

