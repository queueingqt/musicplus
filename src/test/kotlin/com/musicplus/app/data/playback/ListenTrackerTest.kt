package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListenTrackerTest {
    private var sessions = 0
    private var nowMs = 0L
    private val tracker = ListenTracker({ "s${++sessions}" }, { nowMs })

    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 200, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun state(
        id: String, positionMs: Long, durationMs: Long = 200_000, index: Int = 0,
        isPlaying: Boolean = true, queue: List<Track> = listOf(track(id)),
    ) = PlaybackState(queue = queue, currentIndex = index, isPlaying = isPlaying, positionMs = positionMs, durationMs = durationMs)

    private val nothing = PlaybackState()

    /** One state, [afterMs] of wall time after the last one. */
    private fun observe(s: PlaybackState, afterMs: Long = STEP_MS): List<ListenEvent> {
        nowMs += afterMs
        return tracker.observe(s)
    }

    /**
     * Plays [id] from [fromMs] to [toMs] the way the player reports it (a state every [STEP_MS] with the position keeping pace with
     * the clock), and returns every event that produced.
     */
    private fun play(id: String, fromMs: Long, toMs: Long, durationMs: Long = 200_000, index: Int = 0, queue: List<Track> = listOf(track(id))): List<ListenEvent> {
        val events = ArrayList<ListenEvent>()
        var position = fromMs
        events += observe(state(id, position, durationMs, index, queue = queue), afterMs = 0)
        while (position < toMs) {
            position = minOf(position + STEP_MS, toMs)
            events += observe(state(id, position, durationMs, index, queue = queue))
        }
        return events
    }

    private fun List<ListenEvent>.counted() = filterIsInstance<ListenEvent.Counted>()
    private fun List<ListenEvent>.started() = filterIsInstance<ListenEvent.Started>()

    @Test
    fun aListenStartsOnceWhenTheTrackIsAudiblyPlaying() {
        assertEquals(1, observe(state("a", 1_000)).started().size)
        assertTrue(observe(state("a", 1_250)).isEmpty())
    }

    @Test
    fun nothingHappensWhilePausedOrBeforeTheDurationIsKnown() {
        assertTrue(observe(state("a", 1_000, isPlaying = false)).isEmpty())
        assertTrue(observe(state("a", 1_000, durationMs = 0)).isEmpty())
    }

    @Test
    fun aListenCountsAfterHalfTheTrackHasBeenHeardAndOnlyOnce() {
        assertTrue(play("a", 0, 99_000).counted().isEmpty())
        assertEquals(1, play("a", 99_000, 100_000).counted().size)
        assertTrue(play("a", 100_000, 150_000).counted().isEmpty())
    }

    @Test
    fun theThresholdCapsAtFourMinutesForLongTracks() {
        val long = 1_200_000L
        assertTrue(play("a", 0, 239_000, long).counted().isEmpty())
        assertEquals(1, play("a", 239_000, 240_000, long).counted().size)
    }

    @Test
    fun tracksUnderThirtySecondsNeverCount() {
        assertTrue(play("a", 0, 28_000, durationMs = 29_000).counted().isEmpty())
    }

    /** Time paused is not time heard, and pausing does not end the listen. */
    @Test
    fun pausingAndResumingIsOneListenAndPausedTimeIsNotHeard() {
        assertEquals(1, play("a", 0, 60_000).started().size)
        observe(state("a", 60_000, isPlaying = false), afterMs = 500)
        observe(state("a", 60_000, isPlaying = false), afterMs = 120_000)
        val resumed = play("a", 60_000, 99_000)
        assertTrue(resumed.started().isEmpty(), "still the same listen")
        assertTrue(resumed.counted().isEmpty(), "99 s heard, not 100")
        assertEquals(1, play("a", 99_000, 100_000).counted().size)
    }

    // ---- #75: the position is not the new song's own for a moment after a change of song ----

    /** A skip late in a song: the new song's first state still carries the old song's position. */
    @Test
    fun aSongSkippedToIsNotCountedForThePreviousSongsPosition() {
        val queue = listOf(track("a"), track("b"))
        play("a", 0, 155_000, queue = queue, index = 0)
        val staleFirstState = observe(state("b", 155_000, durationMs = 240_000, index = 1, queue = queue))
        assertTrue(staleFirstState.counted().isEmpty(), "b has been heard for no time at all")
        assertEquals(1, staleFirstState.started().size)
        assertTrue(observe(state("b", 300, durationMs = 240_000, index = 1, queue = queue)).counted().isEmpty())
    }

    @Test
    fun aSongSkippedToIsCountedOnlyOnceItHasBeenHeardForItsThreshold() {
        val queue = listOf(track("a"), track("b"))
        play("a", 0, 155_000, queue = queue, index = 0)
        observe(state("b", 155_000, durationMs = 240_000, index = 1, queue = queue))
        assertTrue(play("b", 0, 119_000, durationMs = 240_000, index = 1, queue = queue).counted().isEmpty())
        assertEquals(1, play("b", 119_000, 120_000, durationMs = 240_000, index = 1, queue = queue).counted().size)
    }

    /** A song that ends into the next one: the same stale position, without any tap. */
    @Test
    fun aSongThatEndsIntoTheNextIsNotCountedAtOnce() {
        val queue = listOf(track("a"), track("b"))
        val heardA = play("a", 0, 199_750, queue = queue, index = 0)
        assertEquals(1, heardA.counted().size, "a itself was heard")
        val events = observe(state("b", 200_000, durationMs = 38_000, index = 1, queue = queue))
        assertTrue(events.counted().isEmpty())
        assertEquals(listOf("a"), events.filterIsInstance<ListenEvent.Ended>().map { it.listen.trackId })
        assertEquals(1, events.started().size)
    }

    /** A song resumed from a saved position has not been heard up to it. */
    @Test
    fun aSongResumedFromASavedPositionMustStillBeHeard() {
        assertTrue(play("a", 100_000, 199_000).counted().isEmpty())
        assertEquals(1, play("a", 199_000, 200_000).counted().size)
    }

    @Test
    fun seekingForwardPastTheHalfwayMarkDoesNotCount() {
        play("a", 0, 10_000)
        assertTrue(observe(state("a", 150_000)).counted().isEmpty())
        assertTrue(play("a", 150_000, 180_000).counted().isEmpty(), "only 40 s heard in all")
    }

    @Test
    fun seekingBackAfterItCountedDoesNotCountAgain() {
        assertEquals(1, play("a", 0, 100_000).counted().size)
        observe(state("a", 10_000))
        assertTrue(play("a", 10_000, 150_000).counted().isEmpty())
    }

    /** The player's position updates are coarse, and the app can be slow to be told: a few seconds between states still count. */
    @Test
    fun sparseStatesThatKeepPaceWithTheClockStillCount() {
        observe(state("a", 0), afterMs = 0)
        var position = 0L
        var counted = 0
        while (position < 100_000) {
            position += 5_000
            counted += observe(state("a", position), afterMs = 5_000).counted().size
        }
        assertEquals(1, counted)
    }

    // ---- what a listen is (#58) ----

    /** #58: a song played on its own is always queue index 0, so the second single-song play must still be a listen. */
    @Test
    fun theSameSongPlayedAgainIsANewListen() {
        val first = play("a", 0, 100_000)
        assertEquals(1, first.started().size)
        assertEquals(1, first.counted().size)

        tracker.beginPlay()
        val second = observe(state("a", 1_000))
        assertEquals(1, second.filterIsInstance<ListenEvent.Ended>().size, "the first listen ends when the new play begins")
        assertEquals(1, second.started().size, "the second play is a new listen even at queue index 0")
        assertEquals(1, play("a", 1_000, 101_000).counted().size)
    }

    /** Clearing or shuffling the queue moves the playing track to a new index; that is not a new listen. */
    @Test
    fun theTrackMovingToAnotherQueueIndexIsNotANewListen() {
        val queue = listOf(track("x"), track("a"), track("y"))
        play("a", 0, 100_000, index = 1, queue = queue)
        val moved = observe(state("a", 100_250, index = 0, queue = listOf(track("a"))))
        assertTrue(moved.isEmpty())
    }

    @Test
    fun changingTrackEndsTheOldListenAtItsLastPositionAndStartsTheNext() {
        observe(state("a", 30_000))
        val events = observe(state("b", 500))
        val ended = events.filterIsInstance<ListenEvent.Ended>().single()
        assertEquals("a", ended.listen.trackId)
        assertEquals(30_000, ended.positionMs, "the position the old track was at, not the new track's")
        assertEquals("b", events.started().single().listen.trackId)
    }

    @Test
    fun aListenEndsWhenNoTrackIsCurrentAnyMore() {
        observe(state("a", 7_000))
        val ended = observe(nothing).filterIsInstance<ListenEvent.Ended>().single()
        assertEquals(7_000, ended.positionMs)
    }

    @Test
    fun eachListenGetsItsOwnSessionId() {
        val a = observe(state("a", 0)).started().single().listen.sessionId
        tracker.beginPlay()
        val b = observe(state("a", 0)).started().single().listen.sessionId
        assertTrue(a != b)
    }

    private companion object {
        const val STEP_MS = 250L
    }
}
