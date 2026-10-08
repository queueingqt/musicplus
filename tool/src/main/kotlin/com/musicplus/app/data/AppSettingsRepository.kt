package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val MEDIA_INTEGRITY_CHECKED_VERSION = intPreferencesKey("media_integrity_checked_version")
        val JELLYFIN_DEVICE_ID = stringPreferencesKey("jellyfin_device_id")
    }

    // The settings a screen shows are declared once, in [Settings]; these are the same settings as flows and setters.

    val showAlbumArtwork: Flow<Boolean> = Settings.showAlbumArtwork.observe(dataStore)
    suspend fun setShowAlbumArtwork(enabled: Boolean) = Settings.showAlbumArtwork.set(dataStore, enabled)

    val downloadedOnly: Flow<Boolean> = Settings.downloadedOnly.observe(dataStore)
    suspend fun setDownloadedOnly(enabled: Boolean) = Settings.downloadedOnly.set(dataStore, enabled)

    val scrobblingEnabled: Flow<Boolean> = Settings.scrobbling.observe(dataStore)
    suspend fun setScrobblingEnabled(enabled: Boolean) = Settings.scrobbling.set(dataStore, enabled)

    /** `null` means original/uncapped. */
    val streamQualityWifi: Flow<Int?> = Settings.streamQualityWifi.observe(dataStore)
    suspend fun setStreamQualityWifi(maxBitRateKbps: Int?) = Settings.streamQualityWifi.set(dataStore, maxBitRateKbps)

    val streamQualityCellular: Flow<Int?> = Settings.streamQualityCellular.observe(dataStore)
    suspend fun setStreamQualityCellular(maxBitRateKbps: Int?) = Settings.streamQualityCellular.set(dataStore, maxBitRateKbps)

    val downloadQuality: Flow<Int?> = Settings.downloadQuality.observe(dataStore)
    suspend fun setDownloadQuality(maxBitRateKbps: Int?) = Settings.downloadQuality.set(dataStore, maxBitRateKbps)

    /** Which version of [MediaIntegrity]'s one-time cleanup last ran to completion here; null means never. */
    val mediaIntegrityCheckedVersion: Flow<Int?> = dataStore.data.map { prefs -> prefs[Keys.MEDIA_INTEGRITY_CHECKED_VERSION] }

    suspend fun setMediaIntegrityCheckedVersion(value: Int) {
        dataStore.edit { prefs -> prefs[Keys.MEDIA_INTEGRITY_CHECKED_VERSION] = value }
    }

    /**
     * One id for this app install, shared by every Jellyfin server — not per-server, since it identifies the *device*
     * to whichever Jellyfin server it talks to (shows up in that server's own Dashboard -> Devices), the same
     * physical install regardless of how many Jellyfin servers are added. Generated once, on first use, and reused
     * forever after — a fresh id on every login would register as a brand new device each time. See
     * [authenticateJellyfin]'s doc.
     */
    suspend fun jellyfinDeviceId(): String {
        dataStore.data.map { it[Keys.JELLYFIN_DEVICE_ID] }.first()?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            // Another caller may have generated one first — first write standing wins, everyone converges on the same id.
            if (prefs[Keys.JELLYFIN_DEVICE_ID] == null) prefs[Keys.JELLYFIN_DEVICE_ID] = generated
        }
        return dataStore.data.map { it[Keys.JELLYFIN_DEVICE_ID] }.first()!!
    }
}
