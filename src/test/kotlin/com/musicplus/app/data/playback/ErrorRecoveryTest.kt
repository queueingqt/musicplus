package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorRecoveryTest {
    private val recovery = ErrorRecovery()

    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 100, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun state(ids: List<String>, index: Int, positionMs: Long = 0) =
        PlaybackState(queue = ids.map(::track), currentIndex = index, positionMs = positionMs)

    private val source = PlayerFault(isSourceError = true, diagnostic = "ERROR_CODE_IO_UNSPECIFIED")
    private val everythingPlays: (Track) -> Boolean = { true }

    private fun decide(fault: PlayerFault = source, s: PlaybackState, repeat: RepeatMode = RepeatMode.OFF,
                       playable: (Track) -> Boolean = everythingPlays, now: Long = 1_000_000) =
        recovery.decide(fault, s, repeat, playable, now)

    @Test
    fun onlySourceErrorsOnACurrentTrackAreActedOn() {
        assertEquals(Recovery.None, decide(PlayerFault(false, "ERROR_CODE_DECODING_FAILED"), state(listOf("a"), 0)))
        assertEquals(Recovery.None, decide(source, PlaybackState()))
    }

    @Test
    fun aFailureAtTheStartOfASongIsRetriedOnce() {
        assertEquals(Recovery.Retry, decide(s = state(listOf("a"), 0, positionMs = 800)))
        assertEquals(Recovery.None, decide(s = state(listOf("a"), 0, positionMs = 800), now = 1_010_000), "within the cooldown")
        assertEquals(Recovery.Retry, decide(s = state(listOf("a"), 0, positionMs = 800), now = 1_031_000), "after the cooldown")
    }

    @Test
    fun theCooldownIsPerTrack() {
        assertEquals(Recovery.Retry, decide(s = state(listOf("a", "b"), 0)))
        assertEquals(Recovery.Retry, decide(s = state(listOf("a", "b"), 1)))
    }

    @Test
    fun aFailureMidSongNeverYanksTheListenerBackToTheStart() {
        assertEquals(Recovery.None, decide(s = state(listOf("a"), 0, positionMs = 45_000)))
    }

    @Test
    fun aFileThatIsGoneIsRetriedEvenWhenThePositionIsStale() {
        val gone = PlayerFault(true, "ERROR_CODE_IO_FILE_NOT_FOUND")
        assertEquals(Recovery.Retry, decide(gone, state(listOf("a"), 0, positionMs = 120_000)))
    }

    @Test
    fun anUnplayableSongIsSkippedToTheNextPlayableOne() {
        val playable: (Track) -> Boolean = { it.id != "a" && it.id != "b" }
        val r = decide(s = state(listOf("a", "b", "c"), 0), playable = playable)
        assertEquals(SkipMove.To(2), assertIs<Recovery.Skip>(r).move)
    }

    @Test
    fun nothingLeftToPlayStopsTheQueue() {
        val r = decide(s = state(listOf("a", "b"), 1), playable = { false })
        assertEquals(SkipMove.Stop, assertIs<Recovery.Skip>(r).move)
    }

    @Test
    fun repeatAllWrapsToTheFirstPlayableSong() {
        val playable: (Track) -> Boolean = { it.id == "a" }
        val r = decide(s = state(listOf("a", "b", "c"), 2), repeat = RepeatMode.REPEAT_QUEUE, playable = playable)
        assertEquals(SkipMove.WrapTo(0), assertIs<Recovery.Skip>(r).move)
    }

    /** Skips that keep failing (playback keeps landing back on an unplayable song) stop the queue instead of looping forever. */
    @Test
    fun aRunOfSkipsLongerThanTheQueueStopsInsteadOfLooping() {
        val onlyBPlays: (Track) -> Boolean = { it.id == "b" }
        val moves = List(3) { assertIs<Recovery.Skip>(decide(s = state(listOf("a", "b"), 0), playable = onlyBPlays)).move }
        assertEquals(listOf(SkipMove.To(1), SkipMove.To(1), SkipMove.Stop), moves)
    }

    @Test
    fun aSongThatPlaysAgainEndsTheStreak() {
        val onlyDPlays: (Track) -> Boolean = { it.id == "d" }
        val ids = listOf("a", "b", "c", "d")
        repeat(4) { assertIs<SkipMove.To>(assertIs<Recovery.Skip>(decide(s = state(ids, 0), playable = onlyDPlays)).move) }
        recovery.onPlaying()
        // Without the reset this fifth skip would exceed the queue's size and stop.
        assertIs<SkipMove.To>(assertIs<Recovery.Skip>(decide(s = state(ids, 0), playable = onlyDPlays)).move)
    }

    @Test
    fun aNetworkFailureIndicatesAnUnreachableServer() {
        assertTrue(recovery.indicatesUnreachableServer(PlayerFault(true, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")))
        assertTrue(recovery.indicatesUnreachableServer(PlayerFault(true, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")))
        assertFalse(recovery.indicatesUnreachableServer(PlayerFault(true, "ERROR_CODE_IO_FILE_NOT_FOUND")))
        assertFalse(recovery.indicatesUnreachableServer(PlayerFault(false, "NETWORK_CONNECTION")))
    }
}
