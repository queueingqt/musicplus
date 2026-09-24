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
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppDebugPrefs
import com.musicplus.app.data.AppDisplayPrefs
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.AppScrobblePrefs
import com.musicplus.app.data.AppSettingsRepository
import com.musicplus.app.data.LocalDataRepository
import com.musicplus.app.data.NewerVersion
import com.musicplus.app.data.ServerScope
import com.musicplus.app.data.SyncQueueRepository
import com.musicplus.app.data.VersionCheckRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Top-level Settings — "Server" is the one thing still split into its own
 * sub-page (ServerSettingsScreen.kt), since a multi-server list with its own
 * add/edit/delete actions doesn't fit a flat row. Everything else used to
 * live on a separate "Preferences" page reached by tapping a row here; folded
 * back in directly, since in practice that page was just this whole app's
 * entire set of remaining toggles/settings anyway — the extra tap in front
 * of all of them wasn't earning its keep.
 */
class SettingsScreenViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val localDataRepository: LocalDataRepository,
    syncQueueRepository: SyncQueueRepository,
) : LightViewModel<Unit>() {

    /** Real count, not a toggle — see SyncQueueRepository (issue #24). Zero means either everything's synced or nothing's ever been queued; either way there's nothing to show. */
    val pendingSyncCount: StateFlow<Int> = syncQueueRepository.pendingCount
        .screenState(viewModelScope, 0)

    // AppDisplayPrefs/AppDebugPrefs, not appSettingsRepository directly — this
    // screen gets a fresh ViewModel (and a fresh `.stateIn(...)`) every time
    // it's opened, seeded from a hardcoded default before the real DataStore
    // Flow catches up. Same root cause as Server's "No servers yet" flash
    // (see AppServerPrefs's doc) — only visible here for someone who's
    // actually toggled either off, since the seed happens to match the
    // default otherwise, but the same gap.
    val showAlbumArtwork: StateFlow<Boolean> = AppDisplayPrefs.showAlbumArtwork.value

    fun toggleShowAlbumArtwork() {
        viewModelScope.launch { appSettingsRepository.setShowAlbumArtwork(!showAlbumArtwork.value) }
    }

    val debugLoggingEnabled: StateFlow<Boolean> = AppDebugPrefs.debugLoggingEnabled.value

    fun toggleDebugLogging() {
        viewModelScope.launch { appSettingsRepository.setDebugLoggingEnabled(!debugLoggingEnabled.value) }
    }

    val scrobblingEnabled: StateFlow<Boolean> = AppScrobblePrefs.scrobblingEnabled.value

    fun toggleScrobbling() {
        viewModelScope.launch { appSettingsRepository.setScrobblingEnabled(!scrobblingEnabled.value) }
    }

    /** See [AppScrobblePrefs.lastError]'s doc — set by PlaybackRepository's own scrobble watcher, not this screen. */
    val scrobblingError: StateFlow<String?> = AppScrobblePrefs.lastError.value

    fun clearAllLocalData() {
        viewModelScope.launch { localDataRepository.clearAll() }
    }

    val newerVersion: StateFlow<NewerVersion?> = VersionCheckRepository.newerVersion
}

class SettingsScreen(activity: SealedLightActivity) : LightScreen<Unit, SettingsScreenViewModel>(activity) {

    override val viewModelClass = SettingsScreenViewModel::class.java

    override fun createViewModel(): SettingsScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return SettingsScreenViewModel(graph.appSettingsRepository, graph.localDataRepository, graph.syncQueueRepository)
    }

    @Composable
    override fun Content() {
        val showAlbumArtwork by viewModel.showAlbumArtwork.collectAsState()
        val debugLoggingEnabled by viewModel.debugLoggingEnabled.collectAsState()
        val scrobblingEnabled by viewModel.scrobblingEnabled.collectAsState()
        val scrobblingError by viewModel.scrobblingError.collectAsState()
        val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
        val newerVersion by viewModel.newerVersion.collectAsState()

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                )
            },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                SettingsMenuRow("Server") { navigateTo(::ServerSettingsScreen) }
                ToggleRow(
                    label = "Show album artwork",
                    isOn = showAlbumArtwork,
                    onToggle = { viewModel.toggleShowAlbumArtwork() },
                )
                LightText(
                    text = "Quality Settings",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { navigateTo(::QualitySettingsScreen) }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )
                // Only shown when VersionCheckRepository actually found a
                // newer GitHub release — mirrors the "*" on Home's Settings
                // row. Text-only detail screen, not a tappable link: LightOS
                // tools can't open a browser (see VersionCheckRepository's doc
                // — confirmed via the SDK's own compile-time blocked-patterns
                // check and permission allowlist, not assumed).
                newerVersion?.let { version ->
                    LightText(
                        text = "New Version Available",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { navigateTo({ a -> VersionAvailableScreen(a, version) }) }
                            .padding(vertical = 1f.gridUnitsAsDp()),
                    )
                }
                // Off by default (see AppSettingsRepository.scrobblingEnabled's
                // doc) — unlike every other toggle on this screen, this one
                // sends personal listening history to a linked Last.fm/
                // ListenBrainz account, relayed via the Navidrome server
                // itself. [scrobblingError] surfaces the real server error
                // from the last failed attempt (see AppScrobblePrefs.lastError's
                // doc for why this can't just fail silently) — most likely
                // cause is no account actually linked server-side, but this
                // shows whatever the server itself said, not a guess.
                ToggleRow(
                    label = "Scrobbling",
                    isOn = scrobblingEnabled,
                    onToggle = { viewModel.toggleScrobbling() },
                    subtitle = scrobblingError,
                )
                // Always last — the least likely to be touched day-to-day.
                ToggleRow(
                    // Also gates local debug logging (AppLogger), not just
                    // CrashReporter (issue #41) — kept as one toggle/one
                    // underlying setting (debugLoggingEnabled), just
                    // relabeled per explicit request.
                    label = "Report Crashes",
                    isOn = debugLoggingEnabled,
                    onToggle = { viewModel.toggleDebugLogging() },
                )
                // Not a toggle — a live, real count of writes (favorites,
                // playlist edits) still waiting to reach the server, so a
                // failed/offline write is never just silently identical to a
                // confirmed one (issue #24). Nothing to show, nothing shown.
                if (pendingSyncCount > 0) {
                    LightText(
                        text = if (pendingSyncCount == 1) "1 change waiting to sync" else "$pendingSyncCount changes waiting to sync",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
                LightText(
                    text = "Clear all local data",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            LightModalManager.show(
                                ConfirmModal(
                                    title = "Clear all local data?",
                                    message = buildString {
                                        // Phone Only playlists and the hearts kept on the phone for a server without
                                        // favorites exist nowhere else, so this is the one thing here that cannot be brought back.
                                        val phoneOnlyPlaylists = AppLibraryCache.playlists.value.value.count { ServerScope.isPhone(it.id) }
                                        val phoneOnlyFavorites =
                                            AppLibraryCache.favoriteTracks.value.value.count { it.serverLabel == ServerScope.PHONE_ONLY_LABEL } +
                                                AppLibraryCache.favoriteAlbums.value.value.count { it.serverLabel == ServerScope.PHONE_ONLY_LABEL } +
                                                AppLibraryCache.favoriteArtists.value.value.count { it.serverLabel == ServerScope.PHONE_ONLY_LABEL }
                                        if (phoneOnlyPlaylists > 0 || phoneOnlyFavorites > 0) {
                                            val parts = buildList {
                                                if (phoneOnlyPlaylists > 0) add(if (phoneOnlyPlaylists == 1) "1 Phone Only playlist" else "$phoneOnlyPlaylists Phone Only playlists")
                                                if (phoneOnlyFavorites > 0) add(if (phoneOnlyFavorites == 1) "1 favorite kept only on this phone" else "$phoneOnlyFavorites favorites kept only on this phone")
                                            }
                                            val total = phoneOnlyPlaylists + phoneOnlyFavorites
                                            append("This permanently deletes ${parts.joinToString(" and ")}. ${if (total == 1) "It exists" else "They exist"} nowhere else. ")
                                        }
                                        append(
                                            "Removes downloaded music, cached artwork and lyrics, and the library " +
                                                "cache. Your server login stays saved.",
                                        )
                                        if (pendingSyncCount > 0) {
                                            append(
                                                if (pendingSyncCount == 1) {
                                                    " 1 change waiting to sync will be lost."
                                                } else {
                                                    " $pendingSyncCount changes waiting to sync will be lost."
                                                },
                                            )
                                        }
                                    },
                                    confirmContentDescription = "Clear all local data",
                                    onConfirm = { viewModel.clearAllLocalData() },
                                ),
                                duration = 30.seconds,
                            )
                        }
                        .padding(top = 2f.gridUnitsAsDp()),
                )
                // Which build this is. VERSION_NAME comes from lighttool.toml
                // via the SDK build plugin (scripts/release.sh keeps the SDK
                // checkout's copy in step with this repo's). 1 grid unit above
                // it, not 2: at 2 the page overflowed the screen by a few
                // pixels, so the footer opened half cut off.
                LightText(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuRow(label: String, onClick: () -> Unit) {
    // Copy, not Heading — same size as every other row on this screen
    // (Quality Settings, New Version Available, Clear all local data), per
    // explicit request; this used to stand out as visually larger/bolder.
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp()),
    )
}

// Icon on the left, before the label — not trailing. Confirmed against the
// phone's own LightOS Settings app (General > Haptic Feedback): its toggle
// icon leads the label the same way Airplane Mode's key icon does.
@Composable
private fun ToggleRow(label: String, isOn: Boolean, onToggle: () -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onToggle)
            .padding(vertical = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (isOn) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
            size = 1.5f,
            contentDescription = if (isOn) "On" else "Off",
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = label, variant = LightTextVariant.Copy)
            // Only ever populated by scrobbling's own real-server error right
            // now (see AppScrobblePrefs.lastError's doc) — a generic slot
            // rather than a scrobbling-specific one since any future toggle
            // that can genuinely fail server-side has the identical need.
            if (subtitle != null) {
                LightText(text = subtitle, variant = LightTextVariant.Fine, lighten = true)
            }
        }
    }
}
