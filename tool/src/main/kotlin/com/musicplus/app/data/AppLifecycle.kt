package com.musicplus.app.data

import com.thelightphone.sdk.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the app does when it starts and when the network comes back, in one place that a test can drive. It lived inside `AppGraph.build()`,
 * mixed with composing the graph, where nothing could reach it.
 *
 * **At start:** re-arm the downloads that gave up (on Wi-Fi only: a backlog of failed albums must not be pulled over a data plan unasked) and,
 * once per install, repair songs an older version stored cut short (see [MediaIntegrity]).
 *
 * **When the connection changes to a connected one:** [onReconnected]. Any change of the status counts, not just the connected flag flipping:
 * a VPN taking over the route while Wi-Fi was already "connected" never toggled `isConnected` and used to be missed (confirmed live,
 * 2026-09-22), leaving a server unreachable until a 30 s backstop. Identical repeats are dropped.
 */
class AppLifecycle(
    private val scope: CoroutineScope,
    private val networkStatuses: Flow<NetworkStatus>,
    private val isWifi: () -> Boolean,
    /** Told once for each change to a connected network (and once at launch if already online, which is wanted: drain anything queued offline). */
    private val onReconnected: suspend () -> Unit,
    private val retryFailedDownloads: suspend () -> Unit,
    private val mediaRepair: MediaRepairPolicy,
) {
    /** The one-time repair of songs stored cut short. */
    class MediaRepairPolicy(
        val checkedVersion: suspend () -> Int?,
        val currentVersion: Int,
        val anyServerSetUp: suspend () -> Boolean,
        /** Repairs; with [requeue] the broken songs are fetched again, otherwise just un-marked. True when every check got an answer. */
        val repair: suspend (requeue: Boolean) -> Boolean,
        val recordChecked: suspend (version: Int) -> Unit,
    )

    fun start() {
        scope.launch { if (isWifi()) retryFailedDownloads() }
        scope.launch { repairMediaOnce() }
        scope.launch {
            networkStatuses.distinctUntilChanged().collect { status ->
                if (status.isConnected) onReconnected()
            }
        }
    }

    private suspend fun repairMediaOnce() {
        if ((mediaRepair.checkedVersion() ?: 0) >= mediaRepair.currentVersion) return
        // It has to ask the server for each song's real size.
        networkStatuses.first { it.isConnected }
        if (!mediaRepair.anyServerSetUp()) return
        // Only counts as done when every answer came back, so an offline launch tries again next time.
        if (mediaRepair.repair(isWifi())) mediaRepair.recordChecked(mediaRepair.currentVersion)
    }
}
