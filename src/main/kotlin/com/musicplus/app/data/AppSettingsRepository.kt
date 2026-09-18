package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-level display preferences (as opposed to [ServerConfigRepository]'s
 * connection credentials). Backed by the same shared `DataStore<Preferences>`
 * (`lightContext.dataStore`) — same pattern as [ServerConfigRepository], just a
 * different key namespace, so both repositories can read/write the one SDK-owned
 * DataStore instance without colliding.
 */
class AppSettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val SHOW_ALBUM_ARTWORK = booleanPreferencesKey("show_album_artwork")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
    }

    /** On by default (issue #9 amendment) — absent key means "not yet set", not "off". */
    val showAlbumArtwork: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_ALBUM_ARTWORK] ?: true
    }

    suspend fun setShowAlbumArtwork(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SHOW_ALBUM_ARTWORK] = enabled }
    }

    /**
     * On by default — the whole point is catching crashes/errors before the user
     * knows to go turn it on. Gates [com.musicplus.app.data.AppLogger.d]/[e]; the
     * crash handler itself always writes regardless of this flag.
     */
    val debugLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DEBUG_LOGGING_ENABLED] ?: true
    }

    suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    /**
     * On by default (issue #21). Authoritative for this app on its own — not
     * ANDed with the phone's system-wide haptics preference. An earlier version
     * did AND the two (app can narrow, never widen past system), but that meant
     * this toggle silently did nothing whenever the phone's own Haptic Feedback
     * setting happened to be off, which is not what someone flipping this ON in
     * Music+'s own Preferences expects — confirmed on-device, reported as
     * broken. See [com.musicplus.app.MusicPlusScaffold]'s CompositionLocalProvider
     * for where this actually gets applied (overrides `LocalHapticsEnabled`,
     * which the SDK's `lightClickable` reads — see `LightClickable.kt`).
     */
    val hapticFeedbackEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAPTIC_FEEDBACK_ENABLED] ?: true
    }

    suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.HAPTIC_FEEDBACK_ENABLED] = enabled }
    }
}
