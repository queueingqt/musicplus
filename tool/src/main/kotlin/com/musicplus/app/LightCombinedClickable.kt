package com.musicplus.app

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.thelightphone.sdk.ui.LocalHapticsEnabled

/**
 * `combinedClickable` (tap + long-press) with the same finger-down haptic that
 * the SDK's own `lightClickable` gives plain taps — see `LightClickable.kt`
 * (`sdk/ui/.../LightClickable.kt`). The SDK has no combined-click equivalent of
 * its own, and every track/album row in this app that needs tap-to-play +
 * long-press-for-actions (AlbumDetailScreen, FavoritesScreen,
 * PlaylistDetailScreen, SearchScreen, ServerSettingsScreen) was built on plain
 * `Modifier.combinedClickable` directly, which never touches haptics at all —
 * reported live as "haptics work everywhere except tapping a song to play it,"
 * traced to exactly this gap.
 *
 * Uses standard Compose `LocalHapticFeedback` (`androidx.compose.ui.platform`),
 * not the SDK's own `LightHapticFeedback.click(context)` — that one needs a raw
 * `android.content.Context`, and this build's own compile-time policy blocks
 * `LocalContext` for app code entirely ("use LightScreen APIs instead"; the SDK
 * never exposes a `Context` to a tool module — see `SealedLightContext`, whose
 * `androidContext` is `internal`). `LocalHapticFeedback` is a plain Compose
 * CompositionLocal (no Context needed) and `androidx.compose` is allowlisted as
 * a whole group, so it's available here. Still gated by [LocalHapticsEnabled] —
 * the same signal `lightClickable` reads, which [MusicPlusTheme] supplies
 * app-wide (via the temporary HapticsWorkaround.kt) — so this respects the same
 * on/off value, just through a different underlying vibration call.
 */
fun Modifier.lightCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    val hapticsEnabled = LocalHapticsEnabled.current
    val haptics = LocalHapticFeedback.current
    pointerInput(hapticsEnabled) {
        if (!hapticsEnabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }.combinedClickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
