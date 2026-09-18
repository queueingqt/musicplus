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
    }

    /** On by default (issue #9 amendment) — absent key means "not yet set", not "off". */
    val showAlbumArtwork: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_ALBUM_ARTWORK] ?: true
    }

    suspend fun setShowAlbumArtwork(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SHOW_ALBUM_ARTWORK] = enabled }
    }
}
