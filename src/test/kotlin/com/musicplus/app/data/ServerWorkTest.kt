package com.musicplus.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What running for a server and stopping it promise, with real coroutines. */
class ServerWorkTest {
    private val work = ServerWork()

    @Test
    fun workRunsAndGivesItsResult() = runBlocking<Unit> {
        assertEquals(42, work.run("a") { 42 })
    }

    @Test
    fun aFailureOfTheWorkReachesTheCaller() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { work.run<Int>("a") { error("boom") } }
    }

    @Test
    fun stoppingAServerCancelsItsWorkAndReturnsOnlyOnceItHasStopped() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cleanedUp = CompletableDeferred<Unit>()
        val result = async {
            work.run("a") {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cleanedUp.complete(Unit)
                }
            }
        }
        started.await()
        work.stop("a")
        assertTrue(cleanedUp.isCompleted, "stop waits for the work to finish stopping")
        assertNull(result.await(), "the caller is told it was stopped")
    }

    @Test
    fun theCallerOfStoppedWorkCarriesOnWithWhatItDoesNext() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val after = CompletableDeferred<String>()
        val caller = launch {
            work.run("a") { started.complete(Unit); awaitCancellation() }
            after.complete("next")
        }
        started.await()
        work.stop("a")
        assertEquals("next", after.await())
        caller.join()
        assertTrue(!caller.isCancelled, "stopping the server's work does not cancel the caller")
    }

    @Test
    fun otherServersWorkIsNotTouched() = runBlocking<Unit> {
        val aStarted = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        val b = async { work.run("b") { bRelease.await(); "b done" } }
        val a = async { work.run("a") { aStarted.complete(Unit); awaitCancellation() } }
        aStarted.await()
        work.stop("a")
        bRelease.complete(Unit)
        assertEquals("b done", b.await())
        assertNull(a.await())
    }

    @Test
    fun everyPieceOfWorkRunningForTheServerIsStopped() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val results = (1..3).map { i -> async { work.run("a") { if (i == 3) started.complete(Unit); awaitCancellation() } } }
        started.await()
        delay(20)
        work.stop("a")
        assertEquals(listOf(null, null, null), results.map { it.await() })
    }

    @Test
    fun stoppingAServerWithNothingRunningIsHarmless() = runBlocking<Unit> {
        work.stop("nobody")
        assertEquals("ok", work.run("nobody") { "ok" })
    }

    @Test
    fun aCancelledCallerStillPropagatesItsOwnCancellation() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { work.run("a") { started.complete(Unit); awaitCancellation() } }
        started.await()
        caller.cancel()
        caller.join()
        assertTrue(caller.isCancelled)
    }
}
