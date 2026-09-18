package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persists every saved Navidrome/Subsonic server connection (multi-server
 * support), plus which one is currently active. Backed by the SDK's shared
 * `DataStore<Preferences>` (`lightContext.dataStore` — see SDK reference notes),
 * not a DataStore instance of our own. Preferences DataStore only stores
 * primitives, so the list is kept as one JSON-encoded string rather than
 * per-server indexed keys — simpler to read/write atomically as a whole list.
 *
 * TODO: the password is stored as plain DataStore text. Preferences DataStore has
 * no built-in at-rest encryption — wrap this in an Android Keystore-backed cipher
 * (or store only the credentials needed to mint a Subsonic token, never the raw
 * password) before this ships past a stub.
 */
class ServerConfigRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val SERVERS_JSON = stringPreferencesKey("server_profiles_json")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")

        // Pre-multi-server single-config keys. Never written to anymore — read
        // only as a one-time fallback so an already-configured install doesn't
        // lose its working server the first time it opens this build. See
        // parseServers()'s doc.
        val LEGACY_BASE_URL = stringPreferencesKey("server_base_url")
        val LEGACY_USERNAME = stringPreferencesKey("server_username")
        val LEGACY_PASSWORD = stringPreferencesKey("server_password")
    }

    val servers: Flow<List<ServerProfile>> = dataStore.data.map { parseServers(it) }

    val activeServerId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_SERVER_ID] ?: parseServers(prefs).firstOrNull()?.id
    }

    /** The currently active server's connection config, or null if none configured yet. */
    val serverConfig: Flow<ServerConfig?> = dataStore.data.map { prefs ->
        val servers = parseServers(prefs)
        val activeId = prefs[Keys.ACTIVE_SERVER_ID]
        (servers.find { it.id == activeId } ?: servers.firstOrNull())?.toServerConfig()
    }

    /**
     * Reads the saved server list, migrating a pre-multi-server single config
     * on the fly if [Keys.SERVERS_JSON] hasn't been written yet — a computed
     * fallback, not a one-time destructive write, so it's safe to call from a
     * `Flow.map` (no side effects) and never loses the legacy keys even if this
     * runs before the person ever opens the new Servers screen.
     */
    private fun parseServers(prefs: Preferences): List<ServerProfile> {
        val json = prefs[Keys.SERVERS_JSON]
        if (!json.isNullOrBlank()) {
            return runCatching { Json.decodeFromString<List<ServerProfile>>(json) }.getOrDefault(emptyList())
        }
        val baseUrl = prefs[Keys.LEGACY_BASE_URL]
        val username = prefs[Keys.LEGACY_USERNAME]
        val password = prefs[Keys.LEGACY_PASSWORD]
        if (baseUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) return emptyList()
        return listOf(
            ServerProfile(
                id = "legacy",
                name = runCatching { java.net.URI(baseUrl.trimEnd('/')).host }.getOrNull() ?: "Server",
                baseUrl = baseUrl.trimEnd('/'),
                username = username,
                password = password,
            ),
        )
    }

    /** Adds a new profile, or replaces the one with the same [ServerProfile.id]. First server saved becomes active automatically. */
    suspend fun addOrUpdate(profile: ServerProfile) {
        dataStore.edit { prefs ->
            val current = parseServers(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) current[index] = profile else current += profile
            prefs[Keys.SERVERS_JSON] = Json.encodeToString(current)
            if (prefs[Keys.ACTIVE_SERVER_ID] == null) prefs[Keys.ACTIVE_SERVER_ID] = profile.id
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            val remaining = parseServers(prefs).filterNot { it.id == id }
            prefs[Keys.SERVERS_JSON] = Json.encodeToString(remaining)
            if (prefs[Keys.ACTIVE_SERVER_ID] == id) {
                val nextActive = remaining.firstOrNull()?.id
                if (nextActive != null) prefs[Keys.ACTIVE_SERVER_ID] = nextActive else prefs.remove(Keys.ACTIVE_SERVER_ID)
            }
        }
    }

    suspend fun setActive(id: String) {
        dataStore.edit { prefs -> prefs[Keys.ACTIVE_SERVER_ID] = id }
    }
}
