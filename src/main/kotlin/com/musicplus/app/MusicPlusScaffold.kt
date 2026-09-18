package com.musicplus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.musicplus.app.data.AppHaptics
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Shared root every screen's `Content()` builds on instead of assembling its own
 * bare `Column`/`LightTopBar` (see every screen file for the adoption).
 *
 * Design choice — why a scaffold and not [com.thelightphone.sdk.ui.LightModalManager]:
 * `LightActivity.kt` draws `LightModalManager`'s active modal on top of the
 * current screen and its doc comment calls that "transient" — confirmed by
 * reading `LightModalManager.kt` itself: at most one modal at a time, and it
 * auto-dismisses after a default 2-second timeout unless replaced or manually
 * dismissed first. A now-playing bar needs to stay up indefinitely alongside
 * normal screen content, not auto-expire — the wrong shape for that mechanism.
 * A shared scaffold composable every screen opts into is the fit instead.
 *
 * Renders, top to bottom: [topBar], the screen's own [content] (weighted to fill
 * the remaining space, mirroring how `LightActivity`'s own root `Column` weights
 * the screen slot), a persistent mini-player row when [showMiniPlayer] and
 * something is loaded, then [bottomBar].
 */
@Composable
fun MusicPlusScaffold(
    topBar: @Composable () -> Unit,
    onMiniPlayerClick: () -> Unit = {},
    // Queue icon on the mini-player itself, so the queue is reachable from
    // every screen that shows one (i.e. everywhere but PlayerScreen, which has
    // its own top-bar queue icon since it never shows a mini-player) without
    // adding a queue icon to every individual screen's own top bar. Defaults
    // to a no-op rather than being required, matching onMiniPlayerClick's own
    // convention, but every real call site should pass it.
    onQueueClick: () -> Unit = {},
    showMiniPlayer: Boolean = true,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // App-local haptics override (issue #21) — authoritative on its own, not
    // ANDed with the OS-level LightActivity root's own LocalHapticsEnabled
    // value. An earlier version ANDed the two (app can narrow, never widen
    // past system), which is the more conservative accessibility-respecting
    // choice, but confirmed on-device it meant the app's own toggle silently
    // did nothing whenever the phone's system-wide Haptic Feedback setting
    // happened to be off (logged: system=false app=true combined=false, zero
    // vibration) — reported live as broken, not as expected layering. Someone
    // toggling this on in Music+'s own Preferences expects it to just work.
    val appHapticsEnabled by AppHaptics.enabled.collectAsState()

    CompositionLocalProvider(LocalHapticsEnabled provides appHapticsEnabled) {
        MusicPlusTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Belt-and-suspenders, not itself the on-device-confirmed fix (see
                    // MusicPlusTheme.kt's doc comment for that — it's the LightTheme
                    // wrap, needed for Surface-based Material3 components like
                    // LightTextInputEditor). A plain Column doesn't pick up
                    // MaterialTheme's colorScheme.background on its own the way
                    // Surface/Scaffold-style components do, so this paints it
                    // explicitly rather than assuming whatever's behind it (the
                    // window/decor background) already matches.
                    .background(LightThemeTokens.colors.background),
            ) {
                topBar()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    content = content,
                )
                if (showMiniPlayer) {
                    MiniPlayerBar(onClick = onMiniPlayerClick, onQueueClick = onQueueClick)
                }
                bottomBar()
            }
        }
    }
}

/**
 * Fixed bottom row showing the current track and a play/pause control, visible
 * on every screen that adopts [MusicPlusScaffold] (all but PlayerScreen itself,
 * which passes `showMiniPlayer = false` since the full now-playing UI is already
 * on screen there). Tapping anywhere but the play/pause control navigates to
 * PlayerScreen via [onClick].
 *
 * Uses [PlaybackRepositoryHolder.peek] — same reasoning as HomeScreen previously
 * used it for its "now playing" row: showing state shouldn't itself spend the
 * app's one detached-audio handle by creating a player. Renders nothing until a
 * track has actually been played at least once in this process.
 */
@Composable
private fun MiniPlayerBar(onClick: () -> Unit, onQueueClick: () -> Unit) {
    val playback = PlaybackRepositoryHolder.peek() ?: return
    val state by playback.state.collectAsState(initial = playback.currentSnapshot())
    val track = state.currentTrack ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.12f))
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // Fixed touch-target size around the icon, not just trailing
            // padding — the icon glyph alone (1.5 grid units) is a small,
            // easy-to-miss tap target, and three of these clustered together
            // with only one-sided padding left too little gap between them.
            // Reported live: the skip icon read as unresponsive/too close to
            // play, traced to exactly this.
            modifier = Modifier
                .size(2.5f.gridUnitsAsDp())
                .lightClickable(onClick = onQueueClick),
            contentAlignment = Alignment.Center,
        ) {
            // Sized up from 1.5f (matching PAUSE/PLAY's nominal size param) —
            // LIST's own vector artwork has more internal padding baked in
            // than PAUSE/PLAY's, so at the identical size value it visibly
            // reads smaller. Compensating here since the drawable itself
            // isn't ours to edit (SDK-owned resource).
            LightIcon(icon = LightIcons.LIST, size = 1.9f, contentDescription = "View queue")
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = track.title,
                variant = LightTextVariant.Fine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = track.artistName.orEmpty(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(2.5f.gridUnitsAsDp())
                .lightClickable { playback.skipToNext() },
            contentAlignment = Alignment.Center,
        ) {
            // Same reasoning as LIST above — FAST_FORWARD's own artwork reads
            // visibly smaller than PAUSE/PLAY at an identical size value.
            LightIcon(icon = LightIcons.FAST_FORWARD, size = 1.9f, contentDescription = "Next track")
        }
        Box(
            modifier = Modifier
                .size(2.5f.gridUnitsAsDp())
                .lightClickable { playback.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                // REFRESH, same icon already used for "in progress" download
                // states elsewhere in this app — distinct from PLAY so the
                // loading window between tapping a track and playback
                // actually starting doesn't look identical to "paused,
                // nothing happening." Reported live.
                icon = when {
                    state.isLoading -> LightIcons.REFRESH
                    state.isPlaying -> LightIcons.PAUSE
                    else -> LightIcons.PLAY
                },
                size = 1.5f,
                contentDescription = when {
                    state.isLoading -> "Loading"
                    state.isPlaying -> "Pause"
                    else -> "Play"
                },
            )
        }
    }
}
