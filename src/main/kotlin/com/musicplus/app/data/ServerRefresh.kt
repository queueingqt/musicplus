package com.musicplus.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs a refresh of the phone's cached library against the server(s) that should answer it, once, for [LibraryRepository] and
 * [PlaylistRepository]: `refresh`, `runRefresh` and `live` were copied verbatim between the two.
 *
 * A refresh is a no-op (it leaves the cache as it is) when offline, when a server is not set up, or when the network call itself fails:
 * a stale cache is a better answer than an empty one. The connectivity check alone is not enough, since it says whether a network is
 * up, not whether the configured server is reachable (a Tailscale-hosted server, when Tailscale is not connected, resolves as "online"
 * generally but fails DNS for that one host); an uncaught `UnresolvedAddressException` from an unguarded refresh once took the whole app
 * down on launch (confirmed on-device, 2026-09-17).
 */
class ServerRefresh(
    private val isConnected: () -> Boolean,
    private val apis: ApiLookup,
    /** The servers a list refresh covers: every one that is on. */
    private val shownServerIds: Flow<List<String>>,
    /** Told when a server's refresh worked: see [ServerSyncStatus.refreshed]. */
    private val onRefreshed: suspend (serverId: String) -> Unit,
    /** Each refresh runs under its server, so removing the server stops it before it can write anything more. */
    private val work: ServerWork,
    /** The ids of the servers that are still saved. */
    private val savedServerIds: () -> Set<String> = { AppServerPrefs.servers.value.value.mapTo(HashSet()) { it.id } },
) {
    /**
     * Runs [action] against the api that owns [ownerId] (a refresh of one artist or album), or, with no owner, against every server that
     * is on, each on its own so a slow or failing one never holds up the others (a removed server whose downloads were kept has no
     * login, so it has no api and is skipped).
     */
    suspend fun run(label: String, ownerId: String? = null, action: suspend (MusicApi) -> Unit) {
        if (!isConnected()) return
        if (ownerId != null) {
            apis.forId(ownerId)?.let { runOne(label, it, action) }
            return
        }
        val servers = shownServerIds.first()
        coroutineScope {
            for (id in servers) launch { apis.forServer(id)?.let { runOne(label, it, action) } }
        }
    }

    private suspend fun runOne(label: String, api: MusicApi, action: suspend (MusicApi) -> Unit) {
        work.run(api.serverId) {
            try {
                action(api)
                onRefreshed(api.serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cache left as-is deliberately.
                AppLogger.e("ServerRefresh", "$label failed (server ${api.serverId})", e)
            }
        }
    }

    /** False once the server was removed while a refresh of it was still running: that refresh must not write its rows back. Applies to every refresh, lists and details alike. */
    fun live(api: MusicApi): Boolean = api.serverId in savedServerIds()
}
