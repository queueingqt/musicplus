package com.musicplus.app.data

/**
 * See [WarmedFlow]'s doc. [scrobblingEnabled] mirrors
 * [AppSettingsRepository.scrobblingEnabled]. [lastError] is the one field
 * here that isn't a settings mirror — it's set by [PlaybackRepository]'s
 * scrobble watcher whenever a real `scrobble()` call throws, and cleared on
 * the next successful one. Explicitly required (not swallowed): scrobbling
 * fails silently if the linked Last.fm/ListenBrainz account isn't
 * configured server-side, and that needs to actually reach the person
 * rather than just log to a file nobody's looking at. Surfaced as a
 * subtitle under the Settings toggle rather than an interrupting dialog per
 * failed track — this is background, non-critical activity, not something
 * worth breaking playback flow over.
 */
object AppScrobblePrefs {
    val scrobblingEnabled = WarmedFlow(false)
    val lastError = WarmedFlow<String?>(null)
}
