package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppState
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeScreenViewModel(appState: AppState) : LightViewModel<Unit>() {
    // AppServerPrefs, not AppGraph.from(...).serverConfigRepository.serverConfig directly —
    // this screen gets a fresh ViewModel (and a fresh `.stateIn(...)`) every
    // time it's navigated back to, seeded `false` before the real DataStore
    // Flow catches up — briefly hid the search icon even with a server
    // already configured. Reported live, 2026-09-18 — same root cause as
    // Settings' "Server" row flashing "No servers yet" (see AppServerPrefs's
    // own doc), already fixed once before for the settings (see Settings).
    val isConfigured: StateFlow<Boolean> = appState.servers.isConfigured.value

    // Note: the old "Now playing: <title>" row that used to live here (via a
    // PlaybackRepositoryHolder.peek() StateFlow) was dropped — the persistent
    // mini-player (MusicPlusScaffold, visible on every screen incl. this one) now
    // covers that, and duplicating it here would just be two now-playing
    // indicators competing for attention on the same screen.

    // The refreshAlbumList()/refreshArtists() calls this onScreenShow used to
    // make moved to AppGraph's reconnect-observer — see AppLibraryCache's own
    // doc for why every list screen's per-visit refresh was redundant with a
    // process-lifetime cache that's already kept current.
}

@InitialScreen
class HomeScreen(activity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(activity) {
    override val viewModelClass = HomeScreenViewModel::class.java

    // HomeScreen is @InitialScreen, so this is the first thing to ask for the app state, and asking is what builds the composition root
    // (AppGraph.from) and starts every mirror that fills it. That is now the state's own dependency, not a call made for its side effect:
    // the view model cannot be built without going through the graph that makes its state live. (When this was a discarded call, removing
    // it as "unused" left isConfigured at its seeded `false` for ever and a fresh launch hung on the "Set up your server" splash, 2026-09-18.)
    override fun createViewModel(): HomeScreenViewModel = HomeScreenViewModel(AppGraph.from(lightContext).appState)

    @Composable
    override fun Content() {
        val isConfigured by viewModel.isConfigured.collectAsState()

        // Root screen — no back button (see AlbumListScreen etc. for the
        // leftButton = BACK pattern every non-root screen uses).
        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    center = LightTopBarCenter.Text("Music +"),
                    // The only search icon that opens the real, unscoped
                    // SearchScreen (server-side search3 across all categories)
                    // — every other list screen's search icon just filters that
                    // screen's own already-cached items instead. See
                    // AlbumListScreen etc. Omitted entirely (not just disabled)
                    // when no server is configured yet — there's nothing to
                    // search, and the setup splash below has its own single
                    // "Set up your server" action as the only thing to do here.
                    rightButton = if (isConfigured) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.SEARCH,
                            onClick = { navigateTo(::SearchScreen) },
                            contentDescription = "Search",
                        )
                    } else {
                        null
                    },
                )
            },
        ) {
            if (!isConfigured) {
                SetUpServerSplash { navigateTo(::SettingsScreen) }
            } else {
                LightScrollView(modifier = Modifier.fillMaxWidth()) {
                    MenuRow("Albums") { navigateTo(::AlbumListScreen) }
                    MenuRow("Artists") { navigateTo(::ArtistListScreen) }
                    MenuRow("Songs") { navigateTo(::SongsListScreen) }
                    MenuRow("Playlists") { navigateTo(::PlaylistListScreen) }
                    MenuRow("Favorites") { navigateTo(::FavoritesScreen) }
                    MenuRow("Settings") { navigateTo(::SettingsScreen) }
                }
            }
        }
    }
}

/** Shown instead of the whole menu when no server is configured yet — Albums/Artists/Search/Favorites are all meaningless with nothing to browse. */
@Composable
private fun SetUpServerSplash(onSetUp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(2f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text = "Welcome to Music +", variant = LightTextVariant.Heading)
        LightText(
            text = "Connect to your Navidrome server to start browsing your library.",
            variant = LightTextVariant.Detail,
            align = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 2f.gridUnitsAsDp()),
        )
        LightText(
            text = "Set up your server",
            variant = LightTextVariant.Button,
            modifier = Modifier.lightClickable(onClick = onSetUp),
        )
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Heading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    )
}
