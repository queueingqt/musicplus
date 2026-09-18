package com.musicplus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.musicplus.app.data.PlaybackRepositoryHolder
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
fun LightwaveScaffold(
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
    LightwaveTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Belt-and-suspenders, not itself the on-device-confirmed fix (see
                // LightwaveTheme.kt's doc comment for that — it's the LightTheme
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

/**
 * Fixed bottom row showing the current track and a play/pause control, visible
 * on every screen that adopts [LightwaveScaffold] (all but PlayerScreen itself,
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
        LightIcon(
            icon = LightIcons.LIST,
            size = 1.5f,
            contentDescription = "View queue",
            // Own lightClickable, not the row's onClick — this needs to open
            // QueueScreen specifically, not PlayerScreen like the rest of the row.
            modifier = Modifier
                .lightClickable(onClick = onQueueClick)
                .padding(horizontal = 0.5f.gridUnitsAsDp()),
        )
        LightIcon(
            icon = if (state.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
            size = 1.5f,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            modifier = Modifier.lightClickable { playback.togglePlayPause() },
        )
    }
}
