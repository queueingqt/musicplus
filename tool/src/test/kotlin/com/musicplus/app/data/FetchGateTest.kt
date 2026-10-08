package com.musicplus.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gate through its interface: how many run, in what order, which lane, and what a failure does to the limit. */
class FetchGateTest {
    private var wifi = true
    private val gate = FetchGate(isWifiNow = { wifi })

    private suspend fun until(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(2) }

    private val download = { FetchGate.Priority.DOWNLOAD }

    @Test
    fun itStartsWithFewFetchesOnCellularAndMoreOnWifi() {
        assertEquals(6, FetchGate({ true }).bulkLimit)
        assertEquals(2, FetchGate({ false }).bulkLimit)
    }

    @Test
    fun noMoreBulkFetchesRunAtOnceThanTheLimit() = runBlocking<Unit> {
        wifi = false // limit 2
        val gate = FetchGate({ false })
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val jobs = List(5) {
            async(Dispatchers.Default) {
                gate.run(FetchGate.Lane.BULK, "s$it", download) {
                    val now = running.incrementAndGet()
                    peak.updateAndGet { p -> maxOf(p, now) }
                    release.await()
                    running.decrementAndGet()
                }
            }
        }
        until { running.get() == 2 }
        delay(150)
        assertEquals(2, running.get(), "the other three wait for a slot")
        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(2, peak.get())
    }

    @Test
    fun theMoreImportantWaiterIsServedFirstWhateverTheArrivalOrder() = runBlocking<Unit> {
        val gate = FetchGate({ false }) // limit 2
        val order = CopyOnWriteArrayList<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val holders = listOf(
            async(Dispatchers.Default) { gate.run(FetchGate.Lane.BULK, "hold0", download) { releaseFirst.await() } },
            async(Dispatchers.Default) { gate.run(FetchGate.Lane.BULK, "hold1", download) { releaseSecond.await() } },
        )
        delay(100)
        val late = async(Dispatchers.Default) { gate.run(FetchGate.Lane.BULK, "retry", { FetchGate.Priority.RETRY }) { order += "retry"; releaseSecond.await() } }
        delay(30)
        val important = async(Dispatchers.Default) { gate.run(FetchGate.Lane.BULK, "queued", { FetchGate.Priority.QUEUE }) { order += "queued" } }
        delay(100)
        releaseFirst.complete(Unit) // one slot comes free: it must go to the more important of the two waiting
        until { order.isNotEmpty() }
        assertEquals("queued", order.first())
        releaseSecond.complete(Unit)
        (holders + late + important).forEach { it.await() }
        assertEquals(listOf("queued", "retry"), order.toList())
    }

    @Test
    fun theSongThatIsPlayingNeverWaitsBehindADownloadBacklog() = runBlocking<Unit> {
        val gate = FetchGate({ true }) // 6 slots
        val release = CompletableDeferred<Unit>()
        val holders = List(6) { async(Dispatchers.Default) { gate.run(FetchGate.Lane.BULK, "d$it", download) { release.await() } } }
        delay(150)
        var started = false
        val playing = async(Dispatchers.Default) {
            gate.run(FetchGate.Lane.PLAYBACK, "now", { FetchGate.Priority.NOW_PLAYING }) { started = true }
        }
        until { started }
        playing.await()
        release.complete(Unit)
        holders.forEach { it.await() }
    }

    @Test
    fun noSlotInTimeIsNullNotAnError() = runBlocking<Unit> {
        val gate = FetchGate({ false })
        val release = CompletableDeferred<Unit>()
        val holders = List(2) { async(Dispatchers.Default) { gate.run(FetchGate.Lane.BULK, "h$it", download) { release.await() } } }
        delay(100)
        assertNull(gate.run(FetchGate.Lane.BULK, "waits", download, maxWaitMs = 100) { "never runs" })
        release.complete(Unit)
        holders.forEach { it.await() }
    }

    @Test
    fun aDroppedConnectionOrTimeoutHalvesTheLimitForEitherLaneButAServerSayingNoDoesNot() = runBlocking<Unit> {
        assertEquals(6, gate.bulkLimit)
        assertFailsWith<IOException> { gate.run(FetchGate.Lane.PLAYBACK, "p", { FetchGate.Priority.NOW_PLAYING }) { throw IOException("cut short: got 5 of 100 bytes") } }
        assertEquals(3, gate.bulkLimit, "a playback fetch backs the gate off too")
        assertFailsWith<ServerRefusedException> { gate.run(FetchGate.Lane.BULK, "b", download) { throw ServerRefusedException("server answered 404") } }
        assertEquals(3, gate.bulkLimit, "a 404 says nothing about the link")
    }

    @Test
    fun theLimitNeverFallsBelowItsFloor() = runBlocking<Unit> {
        repeat(4) { assertFailsWith<IOException> { gate.run(FetchGate.Lane.BULK, "b$it", download) { throw IOException("timeout") } } }
        assertEquals(3, gate.bulkLimit)
    }

    @Test
    fun aSwitchFromWifiToCellularIsPickedUpTheNextTimeItIsAsked() = runBlocking<Unit> {
        assertEquals(6, gate.bulkLimit)
        wifi = false
        gate.run(FetchGate.Lane.BULK, "x", download) { }
        assertEquals(2, gate.bulkLimit)
        assertTrue(!gate.isWifi())
    }

    @Test
    fun congestionIsToldFromARefusalByWhatWasThrown() {
        assertTrue(FetchGate.looksLikeCongestion(IOException("connection dropped after 5 bytes")))
        assertTrue(FetchGate.looksLikeCongestion(RuntimeException("HttpRequestTimeoutException")))
        assertTrue(!FetchGate.looksLikeCongestion(ServerRefusedException("server answered 404")))
        assertTrue(!FetchGate.looksLikeCongestion(IllegalStateException("no server configured")))
    }
}
