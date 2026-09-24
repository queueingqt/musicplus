package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppAvailability
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.ListRefresher
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * Flat, unscoped "every song in the library" browse list — distinct from Favorites' Tracks section (favorited only) and Search
 * (server-side query match only). [LibraryRepository.observeAllTracks]'s local cache is otherwise only ever as complete as whichever
 * albums/playlists/searches happen to have been visited, so [LibraryRepository.refreshAllSongs] — the one real "fetch everything"
 * sync this app does, since Subsonic has no direct getAllSongs endpoint — runs at app start and on reconnect, and again in the
 * background each time this page opens. The page itself never waits for it: it reads the warmed cache (see AppLibraryCache's doc,
 * the collection that fix was originally measured against: several real seconds on a warm process, several thousand tracks).
 */
class SongsListScreenViewModel(listRefresher: ListRefresher) : CachedListViewModel(listRefresher, ListRefresher.Target.SONGS) {
    val filter = ListFilter(viewModelScope)
    val tracks = filter.narrowSplit(AppAvailability.songs.value) { track, query ->
        track.title.containsIgnoringCase(query) || track.artistName.containsIgnoringCase(query)
    }
}

class SongsListScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, SongsListScreenViewModel>(activity) {

    override val viewModelClass = SongsListScreenViewModel::class.java

    override fun createViewModel() = SongsListScreenViewModel(AppGraph.from(lightContext).listRefresher)

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val filter by viewModel.filter.query.collectAsState()
        val trackActions = rememberTrackActions(activity, lightContext)

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Songs"),
                    rightButton = filterButton("songs", filter, viewModel.filter::set),
                )
            },
        ) {
            if (tracks.isEmpty) {
                EmptyListNote("songs", filter)
            } else {
                ScreenList(viewModel.scrollPosition) {
                    // TrackRow greys a song out by itself, so the line is all the split adds here.
                    availableItems(tracks, key = { it.id }) { track, _ ->
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
