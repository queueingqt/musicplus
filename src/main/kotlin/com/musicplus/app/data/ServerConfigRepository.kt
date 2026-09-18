package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the Navidrome/Subsonic server connection. Backed by the SDK's shared
 * `DataStore<Preferences>` (`lightContext.dataStore` — see SDK reference notes),
 * not a DataStore instance of our own.
 *
 * TODO: the password is stored as plain DataStore text. Preferences DataStore has
 * no built-in at-rest encryption — wrap this in an Android Keystore-backed cipher
 * (or store only the credentials needed to mint a Subsonic token, never the raw
 * password) before this ships past a stub.
 */
class ServerConfigRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("server_base_url")
        val USERNAME = stringPreferencesKey("server_username")
        val PASSWORD = stringPreferencesKey("server_password")
    }

    val serverConfig: Flow<ServerConfig?> = dataStore.data.map { prefs ->
        val baseUrl = prefs[Keys.BASE_URL]
        val username = prefs[Keys.USERNAME]
        val password = prefs[Keys.PASSWORD]
        if (baseUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            null
        } else {
            ServerConfig(baseUrl = baseUrl.trimEnd('/'), username = username, password = password)
        }
    }

    suspend fun save(config: ServerConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = config.baseUrl.trimEnd('/')
            prefs[Keys.USERNAME] = config.username
            prefs[Keys.PASSWORD] = config.password
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
