package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live mirror of [AppSettingsRepository.scrobblingEnabled], kept in sync by
 * [AppGraph.build] — same pattern as [AppDisplayPrefs]/[AppDebugPrefs]/
 * [AppQualityPrefs]/[AppServerPrefs], for the same reason: SettingsScreen
 * gets a fresh ViewModel (and a fresh `.stateIn(...)`) every time it's
 * opened, seeded from a hardcoded default before the real DataStore Flow
 * catches up.
 *
 * [lastError] is the one thing here that isn't a settings mirror — it's set
 * by [PlaybackRepository]'s scrobble watcher whenever a real `scrobble()`
 * call throws, and cleared on the next successful one. Explicitly required
 * (not swallowed): scrobbling fails silently if the linked Last.fm/
 * ListenBrainz account isn't configured server-side, and that needs to
 * actually reach the person rather than just log to a file nobody's
 * looking at. Surfaced as a subtitle under the Settings toggle rather than
 * an interrupting dialog per failed track — this is background, non-
 * critical activity, not something worth breaking playback flow over.
 */
object AppScrobblePrefs {
    private val _scrobblingEnabled = MutableStateFlow(false)
    val scrobblingEnabled: StateFlow<Boolean> = _scrobblingEnabled.asStateFlow()
    fun setScrobblingEnabled(value: Boolean) {
        _scrobblingEnabled.value = value
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun setLastError(value: String?) {
        _lastError.value = value
    }
}
