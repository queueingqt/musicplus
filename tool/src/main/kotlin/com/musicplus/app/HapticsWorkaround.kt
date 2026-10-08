package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.sdk.refreshHapticsEnabled
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// =====================================================================================
// WORKAROUND(lightsdk-haptics) — TEMPORARY. Waiting on official Light SDK support.
//
// Tracked in issue #21, which stays OPEN until the official support lands and this
// file is deleted. Do not close #21 because this works.
//
// The problem: the SDK only turns tap haptics on (LocalHapticsEnabled, default
// false) after asking LightOS's SDK server for the user's Haptic Feedback setting.
// This app cannot reach that server (its lighttool.toml serverPackage is the
// emulator's, and a real LightOS refuses dev-signed callers), so the setting never
// arrives and plain taps never vibrate. Measured on the phone: haptics worked with
// the app-level override, stopped in e300f60 when it was removed, and stayed off
// even after the SDK's own lookup had failed.
//
// What this does instead: ask LightOS ourselves (refreshHapticsEnabled) and follow
// its answer when there is one, on or off. When LightOS does not answer, default to
// ON: that is what the SDK emulator reports and what this app did before e300f60
// (LightOS's own default is unknown). No app-local toggle: the user's LightOS
// setting is the only switch, whenever it can be read.
//
// Known gap: while LightOS is unreachable this cannot see a user who has turned
// Haptic Feedback off in LightOS, so they still get haptics. That is the price of
// the workaround.
//
// Removal, when the SDK/LightOS delivers the setting to this app on its own:
//   1. Delete this file and HapticsWorkaroundTest.kt.
//   2. Delete the ProvideHapticsWorkaround { } call in MusicPlusTheme.kt.
//   3. Delete the README/SETUP.md notes that mention it (grep for the marker above).
//   4. Then close #21.
// =====================================================================================

/**
 * LightOS's Haptic Feedback setting as this app knows it, defaulting to on.
 *
 * Only ever changed by an actual answer: [fetch] returning `true`/`false` is
 * followed, `null` (LightOS not reachable) leaves whatever we last knew, which is
 * "on" if LightOS has never answered.
 *
 * A failed lookup costs the SDK a 5 s wait, so after a failure the next attempt
 * is held back for [retryAfterFailure]; a success is never held back, so a change
 * made in LightOS Settings is picked up on the next screen. [refresh] is meant to
 * be called from the main thread (composition), which is why there is no locking.
 */
class LightOsHaptics(
    private val fetch: suspend () -> Boolean?,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val retryAfterFailure: Duration = 30.seconds,
) {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var holdUntil: TimeMark? = null
    private var inFlight = false

    suspend fun refresh() {
        if (inFlight) return
        if (holdUntil?.hasNotPassedNow() == true) return
        inFlight = true
        try {
            val answer = fetch()
            holdUntil = if (answer == null) timeSource.markNow() + retryAfterFailure else null
            if (answer != null) _enabled.value = answer
        } finally {
            inFlight = false
        }
    }
}

/** The one instance the app uses, backed by the SDK's own lookup. */
val lightOsHaptics = LightOsHaptics(fetch = { refreshHapticsEnabled() })

/**
 * Overrides the SDK root's [LocalHapticsEnabled] with [lightOsHaptics] for everything
 * below it. Called once, from [MusicPlusTheme], which every screen already goes through.
 */
@Composable
fun ProvideHapticsWorkaround(content: @Composable () -> Unit) {
    val enabled by lightOsHaptics.enabled.collectAsState()
    LaunchedEffect(Unit) { lightOsHaptics.refresh() }
    CompositionLocalProvider(LocalHapticsEnabled provides enabled, content = content)
}
