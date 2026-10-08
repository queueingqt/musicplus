package com.musicplus.app.data

import com.thelightphone.sdk.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the app does at start and on reconnect, driven with fakes: the policy that used to sit inside AppGraph.build(). */
class AppLifecycleTest {
    private val online = NetworkStatus(isConnected = true, isWifi = true, isMetered = false)
    private val offline = NetworkStatus(isConnected = false, isWifi = false, isMetered = false)
    private val onVpn = NetworkStatus(isConnected = true, isWifi = true, isMetered = true)

    private class Harness(val scope: CoroutineScope) {
        val statuses = MutableSharedFlow<NetworkStatus>(replay = 1)
        var wifi = true
        var now = 100 * 3_600_000L
        var lastChecked: Long? = null
        var checkAnswers = true
        val checks = mutableListOf<String>()
        val recorded = mutableListOf<Long>()
        val retries = mutableListOf<String>()
        val reconnects = mutableListOf<String>()
        var integrityChecked: Int? = null
        var serverSetUp = true
        var repairAnswers = true
        val repairs = mutableListOf<Boolean>()

        fun lifecycle() = AppLifecycle(
            scope = scope,
            networkStatuses = statuses,
            isWifi = { wifi },
            onReconnected = { reconnects += "reconnected" },
            versionCheck = AppLifecycle.VersionCheckPolicy(
                lastCheckedAtMs = { lastChecked },
                recordChecked = { recorded += it },
                check = { checks += "check"; checkAnswers },
            ),
            retryFailedDownloads = { retries += "retry" },
            mediaRepair = AppLifecycle.MediaRepairPolicy(
                checkedVersion = { integrityChecked },
                currentVersion = 1,
                anyServerSetUp = { serverSetUp },
                repair = { requeue -> repairs += requeue; repairAnswers },
                recordChecked = { integrityChecked = it },
            ),
            clock = { now },
        )

        suspend fun until(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(2) }
        suspend fun settle() = delay(120)
    }

    private fun run(body: suspend Harness.() -> Unit) = runBlocking<Unit> {
        val h = Harness(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try { h.body() } finally { h.scope.cancel() }
    }

    @Test
    fun theUpdateCheckRunsWhenNeverCheckedAndIsRecordedOnlyWhenGitHubAnswered() = run {
        lastChecked = null
        lifecycle().start()
        until { recorded.isNotEmpty() }
        assertEquals(listOf(now), recorded)
    }

    @Test
    fun aCheckThatCouldNotAskGitHubIsNotRecordedSoTheNextLaunchTriesAgain() = run {
        checkAnswers = false
        lifecycle().start()
        until { checks.isNotEmpty() }
        settle()
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aCheckMadeLessThanADayAgoIsNotRepeated() = run {
        lastChecked = now - 3_600_000L
        lifecycle().start()
        settle()
        assertTrue(checks.isEmpty())
    }

    @Test
    fun aCheckMoreThanADayOldIsRepeated() = run {
        lastChecked = now - 25 * 3_600_000L
        lifecycle().start()
        until { recorded.isNotEmpty() }
        assertEquals(1, checks.size)
    }

    @Test
    fun failedDownloadsAreOnlyReArmedOnWifi() = run {
        wifi = false
        lifecycle().start()
        settle()
        assertTrue(retries.isEmpty(), "never pulled over a data plan unasked")
    }

    @Test
    fun failedDownloadsAreReArmedOnWifi() = run {
        wifi = true
        lifecycle().start()
        until { retries.isNotEmpty() }
    }

    @Test
    fun theMediaRepairWaitsForAConnectionAndOnlyCountsAsDoneWhenEveryAnswerCameBack() = run {
        lifecycle().start()
        settle()
        assertTrue(repairs.isEmpty(), "no connection yet")
        statuses.emit(online)
        until { repairs.isNotEmpty() }
        until { integrityChecked == 1 }
        assertEquals(listOf(true), repairs, "on Wi-Fi the broken songs are fetched again")
    }

    @Test
    fun anIncompleteRepairIsNotRecordedSoItIsTriedAgainNextLaunch() = run {
        repairAnswers = false
        lifecycle().start()
        statuses.emit(online)
        until { repairs.isNotEmpty() }
        settle()
        assertEquals(null, integrityChecked)
    }

    @Test
    fun offWifiTheRepairOnlyUnMarksInsteadOfFetchingAgain() = run {
        wifi = false
        lifecycle().start()
        statuses.emit(NetworkStatus(isConnected = true, isWifi = false, isMetered = true))
        until { repairs.isNotEmpty() }
        assertEquals(listOf(false), repairs)
    }

    @Test
    fun theRepairIsSkippedWhenItAlreadyRanOrNoServerIsSetUp() = run {
        integrityChecked = 1
        lifecycle().start()
        statuses.emit(online)
        settle()
        assertTrue(repairs.isEmpty(), "already done for this version")
    }

    @Test
    fun noServerMeansNoRepair() = run {
        serverSetUp = false
        lifecycle().start()
        statuses.emit(online)
        settle()
        assertTrue(repairs.isEmpty())
    }

    @Test
    fun everyChangeToAConnectedNetworkIsAReconnectNotJustTheConnectedFlagFlipping() = run {
        lifecycle().start()
        statuses.emit(online)
        until { reconnects.size == 1 }
        statuses.emit(online) // an identical repeat
        settle()
        assertEquals(1, reconnects.size, "an identical status is dropped")
        statuses.emit(onVpn) // a VPN takes the route: still connected, but changed
        until { reconnects.size == 2 }
    }

    @Test
    fun goingOfflineIsNotAReconnect() = run {
        lifecycle().start()
        statuses.emit(offline)
        settle()
        assertTrue(reconnects.isEmpty())
        statuses.emit(online)
        until { reconnects.size == 1 }
    }
}
