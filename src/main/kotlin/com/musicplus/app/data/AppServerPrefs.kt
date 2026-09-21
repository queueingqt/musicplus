package com.musicplus.app.data

/**
 * See [WarmedFlow]'s doc. Mirrors [ServerConfigRepository]'s
 * servers/activeServerId/serverConfig.
 */
object AppServerPrefs {
    val servers = WarmedFlow<List<ServerProfile>>(emptyList())
    /** The first server that is on — see [ServerConfigRepository.activeProfile]. */
    val activeServerId = WarmedFlow<String?>(null)
    val enabledServerIds = WarmedFlow<Set<String>>(emptySet())
    val removedServers = WarmedFlow<List<RemovedServer>>(emptyList())
    /** When each server's lists last finished refreshing (epoch ms) — see [ServerSyncStatus]. */
    val lastSyncedAt = WarmedFlow<Map<String, Long>>(emptyMap())
    /** What each server can do — see [CapabilityRegistry]. */
    val capabilities = WarmedFlow<Map<String, ServerCapabilities>>(emptyMap())
    /** Servers that are on but could not be reached at the last request — see [ServerReachability]. */
    val unreachableServerIds = WarmedFlow<Set<String>>(emptySet())
    /** Whether any server is saved (on or off) — the setup splash is for when there is none. */
    val isConfigured = WarmedFlow(false)
}
