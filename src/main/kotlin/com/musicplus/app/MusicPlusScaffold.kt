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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.musicplus.app.data.SleepTimerState
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
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
    // Own tap target on the mini-player's sleep timer icon, straight to
    // SleepTimerPickerScreen — reported live, 2026-09-18: tapping it was
    // expected to jump straight there, not just open Now Playing like the
    // rest of the bar. Defaults to onMiniPlayerClick (same as the rest of
    // the bar) rather than a silent no-op, so a screen that forgets to wire
    // this explicitly still does something sensible.
    onSleepTimerClick: () -> Unit = onMiniPlayerClick,
    showMiniPlayer: Boolean = true,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // No app-local haptics override anymore (removed the "Haptic feedback"
    // Settings toggle, issue #21's fix, and AppHaptics entirely) — LightOS
    // has its own system-wide Haptic Feedback setting already; this just
    // lets whatever LocalHapticsEnabled value LightActivity's own root
    // already provides (reflecting that real OS setting) flow through
    // unchanged, rather than shadowing it with a second, app-specific one.
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
                MiniPlayerBar(onClick = onMiniPlayerClick, onSleepTimerClick = onSleepTimerClick)
            }
            bottomBar()
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
 * No queue icon here (removed — reported live as redundant with Now Playing's
 * own top-bar queue icon, the only place the queue is reachable from now).
 *
 * Uses [PlaybackRepositoryHolder.peek] — same reasoning as HomeScreen previously
 * used it for its "now playing" row: showing state shouldn't itself spend the
 * app's one detached-audio handle by creating a player. Renders nothing until a
 * track has actually been played at least once in this process.
 */
@Composable
private fun MiniPlayerBar(onClick: () -> Unit, onSleepTimerClick: () -> Unit) {
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
        // Sleep timer indicator — visible from anywhere in the app, not just
        // Now Playing's own alarm icon, since the mini-player is the one
        // thing shown on every screen. Own tap target straight to
        // SleepTimerPickerScreen (reported live, 2026-09-18 — tapping it was
        // expected to jump straight there, not just open Now Playing like
        // the rest of the bar), same nested-clickable-region pattern the
        // Next/Play-Pause icons below already use. Live "MM:SS left" badge,
        // same chip treatment as PlayerScreen's own REPEAT_TRACK "1" badge —
        // only for Countdown (EndOfTrack has no fixed duration to count down).
        val sleepTimerState by playback.sleepTimerState.collectAsState()
        if (sleepTimerState != null) {
            // Sized/centered the same as the Next and Play/Pause boxes below
            // — a bare LightIcon here has no internal padding of its own, so
            // it sat visibly closer to its neighbors than they sit to each
            // other despite the same Row-level spacedBy gap. Reported live.
            Box(
                modifier = Modifier
                    .size(2.5f.gridUnitsAsDp())
                    .lightClickable(onClick = onSleepTimerClick),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.ALARM,
                    size = 1.5f,
                    contentDescription = "Sleep timer active",
                )
                val countdown = sleepTimerState as? SleepTimerState.Countdown
                if (countdown != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(LightThemeTokens.colors.background, CircleShape)
                            .padding(horizontal = 0.15f.gridUnitsAsDp()),
                    ) {
                        LightText(text = formatSleepTimerRemaining(countdown.remainingMs), variant = LightTextVariant.Superfine)
                    }
                }
            }
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
