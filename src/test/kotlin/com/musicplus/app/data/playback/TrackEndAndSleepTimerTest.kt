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
            queue = List(queueSize) { track("t$it") }, currentIndex = index, isPlaying = playing,
            positionMs = positionMs, durationMs = durationMs,
        )

    @Test
    fun nothingHappensBeforeTheFinalWindow() {
        assertNull(end.observe(at(50_000), RepeatMode.REPEAT_TRACK, false))
        assertNull(end.observe(at(99_499), RepeatMode.REPEAT_TRACK, false))
    }

    @Test
    fun repeatOneRestartsTheTrackOnceAsItEndsAndNotAgainInTheSameWindow() {
        assertEquals(TrackEndAction.RESTART_TRACK, end.observe(at(99_600), RepeatMode.REPEAT_TRACK, false))
        assertNull(end.observe(at(99_850), RepeatMode.REPEAT_TRACK, false))
    }

    @Test
    fun theSameIndexFiresAgainAfterTheTrackWasPlayedThroughOnceMore() {
        end.observe(at(99_600), RepeatMode.REPEAT_TRACK, false)
        assertNull(end.observe(at(2_000), RepeatMode.REPEAT_TRACK, false))
        assertEquals(TrackEndAction.RESTART_TRACK, end.observe(at(99_700), RepeatMode.REPEAT_TRACK, false))
    }

    @Test
    fun repeatAllOnlyActsOnTheLastTrack() {
        assertNull(end.observe(at(99_600, index = 0, queueSize = 3), RepeatMode.REPEAT_QUEUE, false))
        assertEquals(TrackEndAction.WRAP_QUEUE, end.observe(at(99_600, index = 2, queueSize = 3), RepeatMode.REPEAT_QUEUE, false))
    }

    @Test
    fun withRepeatOffNothingHappens() {
        assertNull(end.observe(at(99_600), RepeatMode.OFF, false))
    }

    @Test
    fun theSleepTimerAtEndOfTrackFires() {
        assertEquals(TrackEndAction.SLEEP, end.observe(at(99_600), RepeatMode.OFF, true))
    }

    /** Asked to sleep at the end of the song and to repeat it: sleep wins, the song is not restarted. */
    @Test
    fun sleepWinsOverRepeat() {
        assertEquals(TrackEndAction.SLEEP, end.observe(at(99_600), RepeatMode.REPEAT_TRACK, true))
    }

    @Test
    fun nothingHappensWhilePausedOrWithoutADuration() {
        assertNull(end.observe(at(99_600, playing = false), RepeatMode.REPEAT_TRACK, false))
        assertNull(end.observe(at(99_600, durationMs = 0), RepeatMode.REPEAT_TRACK, false))
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
