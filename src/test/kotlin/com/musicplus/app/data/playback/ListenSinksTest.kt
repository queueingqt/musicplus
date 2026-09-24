package com.musicplus.app.data.playback

import com.musicplus.app.data.FakeApiLookup
import com.musicplus.app.data.PlayReporter
import com.musicplus.app.data.ScrobbleTarget
import com.musicplus.app.data.StubMusicApi
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sinks, through the api's optional capabilities and a fake lookup: no cast to a backend anywhere. */
class ListenSinksTest {
    private val listen = Listen(trackId = "sub:song1", sessionId = "sess")

    /** A Subsonic-like server: takes a scrobble relay, keeps no resume position. */
    private class RelayApi(id: String) : StubMusicApi(id), ScrobbleTarget {
        val scrobbles = CopyOnWriteArrayList<Pair<String, Boolean>>()
        var failWith: Exception? = null
        override val scrobbler: ScrobbleTarget get() = this
        override suspend fun scrobble(songId: String, submission: Boolean) {
            failWith?.let { throw it }
            scrobbles += songId to submission
        }
    }

    /** A Jellyfin-like server: keeps a resume position and play history, has no scrobble relay. */
    private class ReportingApi(id: String) : StubMusicApi(id), PlayReporter {
        val calls = CopyOnWriteArrayList<String>()
        override val playReporter: PlayReporter get() = this
        override suspend fun reportPlaybackStart(songId: String, positionMs: Long, playSessionId: String) { calls += "start $songId $positionMs $playSessionId" }
        override suspend fun reportPlaybackProgress(songId: String, positionMs: Long, isPaused: Boolean, playSessionId: String) { calls += "progress $songId $positionMs $isPaused $playSessionId" }
        override suspend fun reportPlaybackStopped(songId: String, positionMs: Long, playSessionId: String) { calls += "stopped $songId $positionMs $playSessionId" }
    }

    private val relay = RelayApi("sub")
    private val other = RelayApi("other")
    private val reporting = ReportingApi("jf")
    private val apis = FakeApiLookup(mapOf("sub" to relay, "other" to other, "jf" to reporting))

    private var enabled = true
    private var elsewhere: List<String> = emptyList()
    private var scrobbleOn = setOf("sub", "other")

    private val scrobble = ScrobbleSink(apis, { elsewhere }, { it in scrobbleOn }) { enabled }

    @Test
    fun aListenIsSentAsNowPlayingWhenItStartsAndAsTheRealScrobbleWhenItCounts() = runBlocking<Unit> {
        scrobble.started(listen, 0)
        scrobble.counted(listen)
        assertEquals(listOf("sub:song1" to false, "sub:song1" to true), relay.scrobbles.toList())
    }

    @Test
    fun nothingIsSentWhileScrobblingIsOff() = runBlocking<Unit> {
        enabled = false
        scrobble.started(listen, 0)
        scrobble.counted(listen)
        assertTrue(relay.scrobbles.isEmpty())
    }

    @Test
    fun aServerThatOffersNoRelayIsNotATarget() = runBlocking<Unit> {
        scrobbleOn = setOf("jf")
        scrobble.counted(Listen("jf:song", "s"))
        assertTrue(relay.scrobbles.isEmpty() && other.scrobbles.isEmpty(), "and nothing throws: it has no scrobbler")
    }

    @Test
    fun aSongWhoseServerCannotCountItIsCountedOnAnotherServerThatHasIt() = runBlocking<Unit> {
        scrobbleOn = setOf("other")
        elsewhere = listOf("other:same-song")
        scrobble.counted(listen)
        assertTrue(relay.scrobbles.isEmpty())
        assertEquals(listOf("other:same-song" to true), other.scrobbles.toList(), "the play is counted where it can be")
    }

    @Test
    fun withNoServerThatCanCountItThePlayIsNotCounted() = runBlocking<Unit> {
        scrobbleOn = emptySet()
        scrobble.counted(listen)
        assertTrue(relay.scrobbles.isEmpty() && other.scrobbles.isEmpty())
    }

    @Test
    fun aFailingServerDoesNotThrowIntoPlayback() = runBlocking<Unit> {
        relay.failWith = IOException("connection reset")
        scrobble.counted(listen)
        assertTrue(relay.scrobbles.isEmpty())
    }

    @Test
    fun aServerThatKeepsAResumePositionIsToldEachStepOfTheListen() = runBlocking<Unit> {
        val report = PlayReportSink(apis)
        val jf = Listen("jf:song", "sess-jf")
        assertTrue(report.wantsProgress(jf))
        report.started(jf, 1_000)
        report.progress(jf, 16_000, paused = false)
        report.progress(jf, 20_000, paused = true)
        report.ended(jf, 21_000)
        assertEquals(
            listOf("start jf:song 1000 sess-jf", "progress jf:song 16000 false sess-jf", "progress jf:song 20000 true sess-jf", "stopped jf:song 21000 sess-jf"),
            reporting.calls.toList(),
        )
    }

    @Test
    fun aSongOnAServerThatKeepsNoResumePositionIsNotReported() = runBlocking<Unit> {
        val report = PlayReportSink(apis)
        assertFalse(report.wantsProgress(listen))
        report.started(listen, 0)
        report.progress(listen, 5_000, paused = false)
        report.ended(listen, 6_000)
        assertTrue(reporting.calls.isEmpty())
    }

    @Test
    fun aSongOfAServerThatWasRemovedIsNotReportedAndNothingThrows() = runBlocking<Unit> {
        val report = PlayReportSink(apis)
        val gone = Listen("ghost:song", "s")
        // FakeApiLookup falls back to the first server for an unknown owner only when the id is unscoped, so this is simply no api.
        assertFalse(report.wantsProgress(gone))
        report.started(gone, 0)
    }
}
