package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A server that was removed while its downloaded songs were kept. Its songs stay listed and playable ("<name> (removed)"),
 * and adding a server with the same address and username again picks them back up. No password: nothing here can log in.
 */
@Serializable
data class RemovedServer(val id: String, val name: String, val baseUrl: String, val username: String)

/**
 * Persists every saved Navidrome/Subsonic server connection (multi-server
 * support), plus which of them are switched on. Backed by the SDK's shared
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
class ServerConfigRepository(private val dataStore: DataStore<Preferences>) : ServerProfiles {

    private object Keys {
        val SERVERS_JSON = stringPreferencesKey("server_profiles_json")
        // Older builds read this as "the" active server, so it is still written (the first server that is on); it
        // no longer decides anything here.
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")

        // The servers that are switched on. A key of its own, not a field of [ServerProfile]: that list is decoded
        // without ignoreUnknownKeys, so a build that predates this setting would read a profile with a new field as
        // "no servers" the moment someone rolled back. Absent until the first switch: see [enabledIds].
        val ENABLED_SERVER_IDS = stringSetPreferencesKey("enabled_server_ids")
        val REMOVED_SERVERS = stringPreferencesKey("removed_servers_json")

        // Pre-multi-server single-config keys. Never written to anymore, and
        // actively removed once migrated (see migrateLegacyConfigIfNeeded) —
        // kept only so parseServers()'s read-time fallback and that migration
        // can still see a not-yet-migrated install's working server.
        val LEGACY_BASE_URL = stringPreferencesKey("server_base_url")
        val LEGACY_USERNAME = stringPreferencesKey("server_username")
        val LEGACY_PASSWORD = stringPreferencesKey("server_password")
    }

    override val servers: Flow<List<ServerProfile>> = dataStore.data.map { parseServers(it) }

    /** The ids of the servers that are switched on. */
    val enabledServerIds: Flow<Set<String>> = dataStore.data.map { prefs -> enabledIds(prefs, parseServers(prefs)) }.distinctUntilChanged()

    /** The servers that are switched on, in the order they were saved. */
    val enabledServers: Flow<List<ServerProfile>> = dataStore.data.map { prefs ->
        val all = parseServers(prefs)
        val on = enabledIds(prefs, all)
        all.filter { it.id in on }
    }

    /**
     * The first server that is on, or null when none is. This is what a call that needs "a server" and has no other
     * context talks to (the id in hand names its own server, so most calls never use it).
     */
    override val activeProfile: Flow<ServerProfile?> = enabledServers.map { it.firstOrNull() }

    val activeServerId: Flow<String?> = activeProfile.map { it?.id }

    /** [activeProfile]'s connection config, or null if no server is on. */
    val serverConfig: Flow<ServerConfig?> = activeProfile.map { it?.toServerConfig() }

    /** Servers removed with their downloads kept — see [RemovedServer]. */
    val removedServers: Flow<List<RemovedServer>> = dataStore.data.map { parseRemoved(it) }.distinctUntilChanged()

    /**
     * The servers whose content the app shows: every list, search and favorite is limited to these (see [ServerScope]).
     * The servers that are on, then any removed one whose downloads were kept.
     */
    val shownServerIds: Flow<List<String>> =
        combine(enabledServers, removedServers) { on, gone -> on.map { it.id } + gone.map { it.id } }.distinctUntilChanged()

    /**
     * Which servers are on. Until the first switch nothing is stored, and the server that was active before servers
     * could be switched is the only one on (an install keeps working as it was). Ids of servers that no longer exist
     * are ignored.
     */
    private fun enabledIds(prefs: Preferences, servers: List<ServerProfile>): Set<String> {
        val stored = prefs[Keys.ENABLED_SERVER_IDS]
        if (stored != null) {
            val known = servers.mapTo(HashSet()) { it.id }
            return stored.filterTo(HashSet()) { it in known }
        }
        val activeId = prefs[Keys.ACTIVE_SERVER_ID]
        val primary = servers.find { it.id == activeId } ?: servers.firstOrNull()
        return setOfNotNull(primary?.id)
    }

    /** Keeps [Keys.ACTIVE_SERVER_ID] pointing at the first server that is on, for a build that is rolled back to. */
    private fun writeActive(prefs: MutablePreferences, servers: List<ServerProfile>, enabled: Set<String>) {
        servers.firstOrNull { it.id in enabled }?.let { prefs[Keys.ACTIVE_SERVER_ID] = it.id }
    }

    private var removedFrom: String? = null
    private var removed: List<RemovedServer> = emptyList()

    private fun parseRemoved(prefs: Preferences): List<RemovedServer> {
        val stored = prefs[Keys.REMOVED_SERVERS] ?: return emptyList()
        synchronized(decodedLock) { if (stored == removedFrom) return removed }
        val json = runCatching { EncryptedPrefsCipher.decrypt(stored) }.getOrDefault(stored)
        val result = runCatching { Json.decodeFromString<List<RemovedServer>>(json) }
        result.getOrNull()?.let { list -> synchronized(decodedLock) { removedFrom = stored; removed = list } }
        return result.getOrDefault(emptyList())
    }

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

    /**
     * Adds a new profile (switched on), or replaces the one with the same [ServerProfile.id]. A profile that takes
     * over the id of a [RemovedServer] (see [findRemoved]) picks that server's kept downloads back up.
     */
    suspend fun addOrUpdate(profile: ServerProfile) {
        dataStore.edit { prefs ->
            val current = parseServers(prefs).toMutableList()
            val enabled = enabledIds(prefs, current).toMutableSet()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current += profile
                enabled += profile.id
                prefs[Keys.ENABLED_SERVER_IDS] = enabled
            }
            prefs[Keys.SERVERS_JSON] = EncryptedPrefsCipher.encrypt(Json.encodeToString(current))
            writeActive(prefs, current, enabled)
            val gone = parseRemoved(prefs)
            if (gone.any { it.id == profile.id }) {
                prefs[Keys.REMOVED_SERVERS] = EncryptedPrefsCipher.encrypt(Json.encodeToString(gone.filterNot { it.id == profile.id }))
            }
        }
    }

    /** Switches a server on or off. Off hides its content everywhere and stops refreshing it; nothing else about it changes. */
    suspend fun setEnabled(id: String, on: Boolean) {
        dataStore.edit { prefs ->
            val all = parseServers(prefs)
            val enabled = enabledIds(prefs, all).toMutableSet()
            if (on) enabled += id else enabled -= id
            prefs[Keys.ENABLED_SERVER_IDS] = enabled
            writeActive(prefs, all, enabled)
        }
    }

    /**
     * Forgets a server's login. [keptDownloadsAs] is set when its downloaded songs stay on the phone: they stay listed
     * under that record until they are deleted or a server with the same address and username is added again.
     */
    suspend fun remove(id: String, keptDownloadsAs: RemovedServer? = null) {
        dataStore.edit { prefs ->
            val all = parseServers(prefs)
            val remaining = all.filterNot { it.id == id }
            val enabled = enabledIds(prefs, all) - id
            prefs[Keys.SERVERS_JSON] = EncryptedPrefsCipher.encrypt(Json.encodeToString(remaining))
            prefs[Keys.ENABLED_SERVER_IDS] = enabled
            if (prefs[Keys.ACTIVE_SERVER_ID] == id) prefs.remove(Keys.ACTIVE_SERVER_ID)
            writeActive(prefs, remaining, enabled)
            if (keptDownloadsAs != null) {
                val gone = parseRemoved(prefs).filterNot { it.id == keptDownloadsAs.id } + keptDownloadsAs
                prefs[Keys.REMOVED_SERVERS] = EncryptedPrefsCipher.encrypt(Json.encodeToString(gone))
            }
        }
    }

    /** Drops the record of a removed server (its kept downloads were deleted). */
    suspend fun forgetRemoved(id: String) {
        dataStore.edit { prefs ->
            val gone = parseRemoved(prefs)
            if (gone.none { it.id == id }) return@edit
            prefs[Keys.REMOVED_SERVERS] = EncryptedPrefsCipher.encrypt(Json.encodeToString(gone.filterNot { it.id == id }))
        }
    }

    /** The removed server a new login for [baseUrl] and [username] would re-attach to, if any. */
    suspend fun findRemoved(baseUrl: String, username: String): RemovedServer? {
        val address = baseUrl.trimEnd('/')
        return parseRemoved(dataStore.data.first()).find { it.baseUrl.trimEnd('/').equals(address, ignoreCase = true) && it.username == username }
    }
}
