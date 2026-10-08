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
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.VersionCheckRepository
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

class HomeScreenViewModel : LightViewModel<Unit>() {
    // AppServerPrefs, not AppGraph.from(...).serverConfigRepository.serverConfig directly —
    // this screen gets a fresh ViewModel (and a fresh `.stateIn(...)`) every
    // time it's navigated back to, seeded `false` before the real DataStore
    // Flow catches up — briefly hid the search icon even with a server
    // already configured. Reported live, 2026-09-18 — same root cause as
    // Settings' "Server" row flashing "No servers yet" (see AppServerPrefs's
    // own doc), already fixed once before for AppDisplayPrefs/AppQualityPrefs.
    val isConfigured: StateFlow<Boolean> = AppServerPrefs.isConfigured.value

    // Drives the "*" marker on the Settings row below — VersionCheckRepository
    // itself decides whether newerVersion is non-null (a real newer release).
    val hasNewerVersion: StateFlow<Boolean> = VersionCheckRepository.newerVersion
        .map { it != null }
        .screenState(viewModelScope, false)

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

    override fun createViewModel(): HomeScreenViewModel {
        // Discarded, but not dead code — HomeScreen is @InitialScreen, so this
        // is the one guaranteed call that bootstraps AppGraph.build() (and
        // therefore every mirrorInto — AppServerPrefs.isConfigured included)
        // as early as app launch. Without it, nothing forces the composition
        // root to build until some other screen happens to touch AppGraph
        // first, so isConfigured silently stays at its seeded `false` forever
        // and this screen never leaves the "Set up your server" splash.
        // Reported live, 2026-09-18: a genuinely fresh launch hung on that
        // splash indefinitely (13+ seconds and counting, not just a brief
        // flash) after HomeScreenViewModel's own AppGraph.Graph parameter —
        // its only prior caller of AppGraph.from() — was dropped as unused.
        AppGraph.from(lightContext)
        return HomeScreenViewModel()
    }

    @Composable
    override fun Content() {
        val isConfigured by viewModel.isConfigured.collectAsState()
        val hasNewerVersion by viewModel.hasNewerVersion.collectAsState()

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
                    // "*" mirrors the "New Version Available" row that shows
                    // inside Settings itself when a newer release exists.
                    MenuRow(if (hasNewerVersion) "Settings *" else "Settings") { navigateTo(::SettingsScreen) }
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
