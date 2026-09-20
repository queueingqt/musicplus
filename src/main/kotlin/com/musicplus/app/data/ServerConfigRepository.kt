package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * The JSON blob (including the password) is encrypted with
 * [EncryptedPrefsCipher] before it's written — Preferences DataStore has no
 * built-in at-rest encryption of its own. [parseServers] falls back to
 * treating the stored value as plain (pre-encryption) JSON if decryption
 * fails, so an install that already has a server saved from before this
 * change doesn't lose it — the next [addOrUpdate] re-writes it encrypted.
 */
class ServerConfigRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val SERVERS_JSON = stringPreferencesKey("server_profiles_json")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")

        // Pre-multi-server single-config keys. Never written to anymore, and
        // actively removed once migrated (see migrateLegacyConfigIfNeeded) —
        // kept only so parseServers()'s read-time fallback and that migration
        // can still see a not-yet-migrated install's working server.
        val LEGACY_BASE_URL = stringPreferencesKey("server_base_url")
        val LEGACY_USERNAME = stringPreferencesKey("server_username")
        val LEGACY_PASSWORD = stringPreferencesKey("server_password")
    }

    val servers: Flow<List<ServerProfile>> = dataStore.data.map { parseServers(it) }

    val activeServerId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_SERVER_ID] ?: parseServers(prefs).firstOrNull()?.id
    }

    /** The currently active server's whole profile, or null if none configured yet. */
    val activeProfile: Flow<ServerProfile?> = dataStore.data.map { prefs ->
        val servers = parseServers(prefs)
        val activeId = prefs[Keys.ACTIVE_SERVER_ID]
        servers.find { it.id == activeId } ?: servers.firstOrNull()
    }

    /** The currently active server's connection config, or null if none configured yet. */
    val serverConfig: Flow<ServerConfig?> = activeProfile.map { it?.toServerConfig() }

    /**
     * The servers whose content the app shows right now — every list, search and favorite is limited to these
     * (see [ServerScope]). One at a time for now (the active one); it is the single place that changes when
     * more than one server can be shown together.
     */
    val shownServerIds: Flow<List<String>> = activeProfile.map { listOfNotNull(it?.id) }.distinctUntilChanged()

    /**
     * Reads the saved server list, migrating a pre-multi-server single config
     * on the fly if [Keys.SERVERS_JSON] hasn't been written yet — a computed
     * fallback, not a one-time destructive write, so it's safe to call from a
     * `Flow.map` (no side effects) and never loses the legacy keys even if this
     * runs before the person ever opens the new Servers screen.
     */
    // The last stored value that decoded, and what it decoded to. Every list screen starts a collector on
    // servers/activeProfile, and each one re-ran the keystore decrypt plus the JSON decode (21-186 ms
    // measured on the phone) before its list could emit; the stored value rarely changes, so decode it once.
    private val decodedLock = Any()
    private var decodedFrom: String? = null
    private var decoded: List<ServerProfile> = emptyList()

    private fun parseServers(prefs: Preferences): List<ServerProfile> {
        val stored = prefs[Keys.SERVERS_JSON]
        if (!stored.isNullOrBlank()) {
            synchronized(decodedLock) { if (stored == decodedFrom) return decoded }
            // Pre-encryption installs have this key holding plain JSON already —
            // fall back to reading it as-is rather than losing a working server
            // config. addOrUpdate()/remove() always write the encrypted form, so
            // this self-heals on the next write.
            val json = runCatching { EncryptedPrefsCipher.decrypt(stored) }.getOrDefault(stored)
            val result = runCatching { Json.decodeFromString<List<ServerProfile>>(json) }
            // Only a successful decode is remembered: a failed one may be a transient keystore error.
            result.getOrNull()?.let { servers -> synchronized(decodedLock) { decodedFrom = stored; decoded = servers } }
            return result.getOrDefault(emptyList())
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

    /**
     * One-time migration: if the pre-multi-server legacy keys still hold a
     * config, folds it into [Keys.SERVERS_JSON] (encrypted, same as any other
     * write) and removes the legacy keys — so a credential that
     * [parseServers]'s read-time fallback already surfaces to the rest of the
     * app doesn't also sit forever as a second, permanently-plaintext copy on
     * disk. No-op once the legacy keys are gone. Called once at startup from
     * [AppGraph.build].
     */
    suspend fun migrateLegacyConfigIfNeeded() {
        dataStore.edit { prefs ->
            val hasLegacy = !prefs[Keys.LEGACY_BASE_URL].isNullOrBlank() &&
                !prefs[Keys.LEGACY_USERNAME].isNullOrBlank() &&
                !prefs[Keys.LEGACY_PASSWORD].isNullOrBlank()
            if (!hasLegacy) return@edit

            if (prefs[Keys.SERVERS_JSON].isNullOrBlank()) {
                val migrated = parseServers(prefs)
                prefs[Keys.SERVERS_JSON] = EncryptedPrefsCipher.encrypt(Json.encodeToString(migrated))
                if (prefs[Keys.ACTIVE_SERVER_ID] == null) migrated.firstOrNull()?.let { prefs[Keys.ACTIVE_SERVER_ID] = it.id }
            }
            prefs.remove(Keys.LEGACY_BASE_URL)
            prefs.remove(Keys.LEGACY_USERNAME)
            prefs.remove(Keys.LEGACY_PASSWORD)
        }
    }

    /** Adds a new profile, or replaces the one with the same [ServerProfile.id]. First server saved becomes active automatically. */
    suspend fun addOrUpdate(profile: ServerProfile) {
        dataStore.edit { prefs ->
            val current = parseServers(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) current[index] = profile else current += profile
            prefs[Keys.SERVERS_JSON] = EncryptedPrefsCipher.encrypt(Json.encodeToString(current))
            if (prefs[Keys.ACTIVE_SERVER_ID] == null) prefs[Keys.ACTIVE_SERVER_ID] = profile.id
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            val remaining = parseServers(prefs).filterNot { it.id == id }
            prefs[Keys.SERVERS_JSON] = EncryptedPrefsCipher.encrypt(Json.encodeToString(remaining))
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
