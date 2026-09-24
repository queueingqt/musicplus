package com.musicplus.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Whether each server can be reached right now. One flag per server, kept by the requests the app makes anyway
 * ([SubsonicClient] reports every one), plus a quiet re-check every [RECHECK_INTERVAL_MS] while a server is down, so it
 * comes back by itself without anyone having to tap anything.
 *
 * A server that answered, even with an error (wrong login, "not found"), is reachable. Only a request that never got an
 * answer (no route, refused, timed out) or a proxy's 502-504 counts as down. A server that is switched off is not "down":
 * it is just off, and is left out of this.
 */
class ServerReachability(
    private val scope: CoroutineScope,
    private val apiHolder: ApiLookup,
) {
    private val down = MutableStateFlow<Set<String>>(emptySet())
    private var recheckJob: Job? = null

    /** Called by [SubsonicClient] after each request to [serverId]. */
    fun report(serverId: String, reachable: Boolean) {
        val changed = down.updateAndReportChange { if (reachable) it - serverId else it + serverId }
        if (!changed) return
        AppServerPrefs.unreachableServerIds.set(down.value)
        AppLogger.d("Reachability", "server $serverId is ${if (reachable) "reachable again" else "not reachable"}")
        if (!reachable) startRechecking()
    }

    /** Asks every server that is down again right away (the network just came back). */
    fun recheckNow() {
        scope.launch { for (id in down.value.toList()) recheck(id) }
    }

    fun forget(serverId: String) {
        report(serverId, reachable = true)
    }

    private fun MutableStateFlow<Set<String>>.updateAndReportChange(change: (Set<String>) -> Set<String>): Boolean {
        var changed = false
        update { before ->
            val after = change(before)
            changed = after != before
            after
        }
        return changed
    }

    private fun startRechecking() {
        if (recheckJob?.isActive == true) return
        recheckJob = scope.launch {
            while (down.value.isNotEmpty()) {
                delay(RECHECK_INTERVAL_MS)
                down.value.toList().forEach { recheck(it) }
            }
        }
    }

    private suspend fun recheck(serverId: String) {
        // Turned off or removed while down: nothing to wait for.
        if (serverId !in AppServerPrefs.enabledServerIds.value.value) {
            report(serverId, reachable = true)
            return
        }
        val api = apiHolder.forServer(serverId)
        if (api == null) {
            report(serverId, reachable = true)
            return
        }
        // The request itself reports the outcome through SubsonicClient.
        api.ping()
    }

    private companion object {
        const val RECHECK_INTERVAL_MS = 30_000L
    }
}
