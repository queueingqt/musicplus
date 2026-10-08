package com.musicplus.app.data

/**
 * What the last scrobble attempt said, for Settings to show beside the Scrobbling switch (see [WarmedFlow]). Runtime state, not a setting:
 * the switch itself is [Settings.scrobbling].
 */
object AppScrobblePrefs {
    /** The error from the last failed scrobble, cleared on the next success. */
    val lastError = WarmedFlow<String?>(null)
}
