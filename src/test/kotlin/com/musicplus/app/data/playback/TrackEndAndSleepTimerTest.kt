package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackEndTest {
    private val end = TrackEnd()

    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 100, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun at(positionMs: Long, index: Int = 0, queueSize: Int = 1, durationMs: Long = 100_000, playing: Boolean = true) =
        PlaybackState(
            queue = List(queueSize) { track("t$it") }, currentIndex = index, isPlaying = isPlaying(playing),
            positionMs = positionMs, durationMs = durationMs,
        )

    private fun isPlaying(playing: Boolean) = playing

    /** The song has been heard part of the way through, so a reading near its end is its own (see [TrackEnd]). */
    private fun heard(index: Int = 0, queueSize: Int = 1) {
        assertNull(end.observe(at(20_000, index, queueSize), RepeatMode.OFF, false))
    }

    @Test
    fun nothingHappensBeforeTheFinalWindow() {
        heard()
        assertNull(end.observe(at(50_000), RepeatMode.REPEAT_TRACK, false))
        assertNull(end.observe(at(99_499), RepeatMode.REPEAT_TRACK, false))
    }

    @Test
    fun repeatOneRestartsTheTrackOnceAsItEndsAndNotAgainInTheSameWindow() {
        heard()
        assertEquals(TrackEndAction.RESTART_TRACK, end.observe(at(99_600), RepeatMode.REPEAT_TRACK, false))
        assertNull(end.observe(at(99_850), RepeatMode.REPEAT_TRACK, false))
    }

    @Test
    fun theSameIndexFiresAgainAfterTheTrackWasPlayedThroughOnceMore() {
        heard()
        end.observe(at(99_600), RepeatMode.REPEAT_TRACK, false)
        assertNull(end.observe(at(2_000), RepeatMode.REPEAT_TRACK, false))
        assertEquals(TrackEndAction.RESTART_TRACK, end.observe(at(99_700), RepeatMode.REPEAT_TRACK, false))
    }

    @Test
    fun repeatAllOnlyActsOnTheLastTrack() {
        heard(index = 0, queueSize = 3)
        assertNull(end.observe(at(99_600, index = 0, queueSize = 3), RepeatMode.REPEAT_QUEUE, false))
        heard(index = 2, queueSize = 3)
        assertEquals(TrackEndAction.WRAP_QUEUE, end.observe(at(99_600, index = 2, queueSize = 3), RepeatMode.REPEAT_QUEUE, false))
    }

    @Test
    fun withRepeatOffNothingHappens() {
        heard()
        assertNull(end.observe(at(99_600), RepeatMode.OFF, false))
    }

    @Test
    fun theSleepTimerAtEndOfTrackFires() {
        heard()
        assertEquals(TrackEndAction.SLEEP, end.observe(at(99_600), RepeatMode.OFF, true))
    }

    /** Asked to sleep at the end of the song and to repeat it: sleep wins, the song is not restarted. */
    @Test
    fun sleepWinsOverRepeat() {
        heard()
        assertEquals(TrackEndAction.SLEEP, end.observe(at(99_600), RepeatMode.REPEAT_TRACK, true))
    }

    @Test
    fun nothingHappensWhilePausedOrWithoutADuration() {
        heard()
        assertNull(end.observe(at(99_600, playing = false), RepeatMode.REPEAT_TRACK, false))
        assertNull(end.observe(at(99_600, durationMs = 0), RepeatMode.REPEAT_TRACK, false))
    }

    // ---- #78: for one tick after a change of song the position is still the previous song's ----

    /** The song before the last ends: the new (last) index arrives one tick before the position resets, and repeat-all took that for the last song ending. */
    @Test
    fun theLastSongIsNotWrappedAtOnceBecauseTheOneBeforeItEnded() {
        heard(index = 0, queueSize = 2)
        assertNull(end.observe(at(99_600, index = 0, queueSize = 2), RepeatMode.REPEAT_QUEUE, false))
        val staleTick = end.observe(at(99_900, index = 1, queueSize = 2), RepeatMode.REPEAT_QUEUE, false)
        assertNull(staleTick, "the last song has not been heard at all")
        assertNull(end.observe(at(300, index = 1, queueSize = 2), RepeatMode.REPEAT_QUEUE, false))
        assertNull(end.observe(at(60_000, index = 1, queueSize = 2), RepeatMode.REPEAT_QUEUE, false))
        assertEquals(TrackEndAction.WRAP_QUEUE, end.observe(at(99_600, index = 1, queueSize = 2), RepeatMode.REPEAT_QUEUE, false), "and it wraps when it really ends")
    }

    @Test
    fun aSleepTimerSetAfterASongEndedDoesNotFireOnTheNextSongsFirstTick() {
        heard(index = 0, queueSize = 2)
        end.observe(at(99_600, index = 0, queueSize = 2), RepeatMode.OFF, false)
        assertNull(end.observe(at(99_900, index = 1, queueSize = 2), RepeatMode.OFF, true))
    }

    @Test
    fun aSongIsNotTreatedAsEndingWhenPlayFirstFindsItInsideItsFinalWindow() {
        assertNull(end.observe(at(99_600), RepeatMode.REPEAT_TRACK, false), "never seen away from the end: no way to know the position is its own")
    }

    @Test
    fun aQueueEditThatMovesTheSameSongToAnotherIndexIsNotAChangeOfSongForLongIfItIsHeardAgain() {
        heard(index = 1, queueSize = 3)
        assertNull(end.observe(at(30_000, index = 0, queueSize = 3), RepeatMode.REPEAT_TRACK, false))
        assertEquals(TrackEndAction.RESTART_TRACK, end.observe(at(99_600, index = 0, queueSize = 3), RepeatMode.REPEAT_TRACK, false))
    }
}

class SleepTimerTest {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var slept = 0
    private val timer = SleepTimer(scope, onSleep = { slept++ }, tickMs = 5)

    private suspend fun until(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(2) }

    @Test
    fun aCountdownPausesOnceWhenItRunsOutAndClearsItself() = runBlocking<Unit> {
        timer.startFor(40)
        assertTrue(timer.state.value is SleepTimerState.Countdown)
        until { slept == 1 }
        until { timer.state.value == null }
        delay(30)
        assertEquals(1, slept)
    }

    @Test
    fun theCountdownTicksDownAndKeepsItsOriginalTotal() = runBlocking<Unit> {
        timer.startFor(200)
        until { (timer.state.value as? SleepTimerState.Countdown)?.remainingMs?.let { it < 200 } == true }
        assertEquals(200, (timer.state.value as SleepTimerState.Countdown).totalMs)
        timer.cancel()
    }

    @Test
    fun cancellingStopsItWithoutSleeping() = runBlocking<Unit> {
        timer.startFor(60)
        timer.cancel()
        assertNull(timer.state.value)
        delay(120)
        assertEquals(0, slept)
    }

    @Test
    fun startingAgainReplacesTheRunningCountdown() = runBlocking<Unit> {
        timer.startFor(10_000)
        timer.startFor(30)
        until { slept == 1 }
        delay(50)
        assertEquals(1, slept)
    }

    @Test
    fun endOfTrackDoesNotTickAndFiresOnlyWhenToldTo() = runBlocking<Unit> {
        timer.startAtEndOfTrack()
        assertTrue(timer.endOfTrackArmed)
        delay(60)
        assertEquals(0, slept)
        timer.fire()
        assertEquals(1, slept)
        assertNull(timer.state.value)
        assertFalse(timer.endOfTrackArmed)
    }

    @Test
    fun switchingToEndOfTrackStopsTheCountdown() = runBlocking<Unit> {
        timer.startFor(50)
        timer.startAtEndOfTrack()
        delay(120)
        assertEquals(0, slept)
        assertTrue(timer.state.value is SleepTimerState.EndOfTrack)
    }
}
