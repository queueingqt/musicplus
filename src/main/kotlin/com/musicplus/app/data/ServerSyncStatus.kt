package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * When each server's lists last finished refreshing, for the Servers screen's "Synced 2h ago". Kept in the shared
 * DataStore so it survives a restart, and mirrored into [AppServerPrefs.lastSyncedAt] so a screen has it on its first
 * frame. Any of a server's lists finishing counts: there is no single "the sync".
 */
class ServerSyncStatus(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("server_last_synced_json")
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    val lastSynced = dataStore.data.map { prefs -> decode(prefs[key]) }

    /** Called after one of [serverId]'s lists refreshed. Writes at most every [MIN_WRITE_GAP_MS] per server: five lists finish within seconds of each other. */
    suspend fun refreshed(serverId: String, now: Long = System.currentTimeMillis()) {
        val known = AppServerPrefs.lastSyncedAt.value.value[serverId]
        if (known != null && now - known < MIN_WRITE_GAP_MS) return
        dataStore.edit { prefs -> prefs[key] = Json.encodeToString(serializer, decode(prefs[key]) + (serverId to now)) }
    }

    /** Forgets a removed server. */
    suspend fun forget(serverId: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (serverId in current) prefs[key] = Json.encodeToString(serializer, current - serverId)
        }
    }

    private fun decode(stored: String?): Map<String, Long> =
        stored?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        const val MIN_WRITE_GAP_MS = 30_000L
    }
}
