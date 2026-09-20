package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        val SCROBBLING_ENABLED = booleanPreferencesKey("scrobbling_enabled")
        // Int, not a serialized enum — see StreamQuality's doc: this is a
        // plain kbps value (or absent = original/uncapped), not a closed set
        // of tiers. A sentinel rather than a second "is this original"
        // boolean key: DataStore's Preferences has no way to store a real
        // `null`, and 0 is never a meaningful bitrate to actually request.
        val STREAM_QUALITY_WIFI = intPreferencesKey("stream_quality_wifi_kbps")
        val STREAM_QUALITY_CELLULAR = intPreferencesKey("stream_quality_cellular_kbps")
        val DOWNLOAD_QUALITY = intPreferencesKey("download_quality_kbps")
        val LAST_VERSION_CHECK_AT_MS = longPreferencesKey("last_version_check_at_ms")
        val MEDIA_INTEGRITY_CHECKED_VERSION = intPreferencesKey("media_integrity_checked_version")
    }

    private companion object {
        const val ORIGINAL_SENTINEL = 0
    }

    private fun Preferences.streamQualityKbps(key: Preferences.Key<Int>, default: Int?): Int? =
        when (val stored = this[key]) {
            null -> default
            ORIGINAL_SENTINEL -> null
            else -> stored
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
     * Off by default — unlike the other toggles here, this one sends personal
     * listening history to a linked Last.fm/ListenBrainz account (relayed via
     * Navidrome itself; this app never talks to either service directly), so
     * it's opt-in rather than opt-out.
     */
    val scrobblingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SCROBBLING_ENABLED] ?: false
    }

    suspend fun setScrobblingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SCROBBLING_ENABLED] = enabled }
    }

    /**
     * 320kbps by default — matches this app's original (pre-issue-#7-fix)
     * streaming behavior, before quality became configurable at all: Wi-Fi
     * is the common case where bandwidth isn't a real constraint. `null`
     * means original/uncapped.
     */
    val streamQualityWifi: Flow<Int?> = dataStore.data.map { it.streamQualityKbps(Keys.STREAM_QUALITY_WIFI, 320) }

    suspend fun setStreamQualityWifi(maxBitRateKbps: Int?) {
        dataStore.edit { prefs -> prefs[Keys.STREAM_QUALITY_WIFI] = maxBitRateKbps ?: ORIGINAL_SENTINEL }
    }

    /**
     * 192kbps by default, deliberately lower than [streamQualityWifi]'s —
     * cellular data is more often limited/metered than Wi-Fi, so this starts
     * more conservative rather than assuming the Wi-Fi default is always
     * right. Change it in Preferences if that tradeoff isn't right for you.
     */
    val streamQualityCellular: Flow<Int?> = dataStore.data.map { it.streamQualityKbps(Keys.STREAM_QUALITY_CELLULAR, 192) }

    suspend fun setStreamQualityCellular(maxBitRateKbps: Int?) {
        dataStore.edit { prefs -> prefs[Keys.STREAM_QUALITY_CELLULAR] = maxBitRateKbps ?: ORIGINAL_SENTINEL }
    }

    /**
     * Original (uncapped) by default — tapping "Download" is already an
     * explicit "I want this available offline" choice, so keeping the source
     * file's full quality unless told otherwise matches that intent, and
     * matches DownloadRepository's own behavior from before this setting
     * existed.
     */
    val downloadQuality: Flow<Int?> = dataStore.data.map { it.streamQualityKbps(Keys.DOWNLOAD_QUALITY, null) }

    suspend fun setDownloadQuality(maxBitRateKbps: Int?) {
        dataStore.edit { prefs -> prefs[Keys.DOWNLOAD_QUALITY] = maxBitRateKbps ?: ORIGINAL_SENTINEL }
    }

    /** Null means never checked — VersionCheckRepository's caller (AppGraph.build) treats that the same as "stale, check now." */
    val lastVersionCheckAtMs: Flow<Long?> = dataStore.data.map { prefs -> prefs[Keys.LAST_VERSION_CHECK_AT_MS] }

    suspend fun setLastVersionCheckAtMs(value: Long) {
        dataStore.edit { prefs -> prefs[Keys.LAST_VERSION_CHECK_AT_MS] = value }
    }

    /** Which version of [MediaIntegrity]'s one-time cleanup last ran to completion here; null means never. */
    val mediaIntegrityCheckedVersion: Flow<Int?> = dataStore.data.map { prefs -> prefs[Keys.MEDIA_INTEGRITY_CHECKED_VERSION] }

    suspend fun setMediaIntegrityCheckedVersion(value: Int) {
        dataStore.edit { prefs -> prefs[Keys.MEDIA_INTEGRITY_CHECKED_VERSION] = value }
    }
}
