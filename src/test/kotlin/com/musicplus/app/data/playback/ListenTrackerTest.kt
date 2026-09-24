package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListenTrackerTest {
    private var sessions = 0
    private val tracker = ListenTracker { "s${++sessions}" }

    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 200, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun playing(
        id: String, positionMs: Long = 1_000, durationMs: Long = 200_000, index: Int = 0,
        isPlaying: Boolean = true, queue: List<Track> = listOf(track(id)),
    ) = PlaybackState(queue = queue, currentIndex = index, isPlaying = isPlaying, positionMs = positionMs, durationMs = durationMs)

    private val nothing = PlaybackState()

    @Test
    fun aListenStartsOnceWhenTheTrackIsAudiblyPlaying() {
        assertEquals(1, tracker.observe(playing("a")).filterIsInstance<ListenEvent.Started>().size)
        assertTrue(tracker.observe(playing("a", positionMs = 2_000)).isEmpty())
    }

    @Test
    fun nothingHappensWhilePausedOrBeforeTheDurationIsKnown() {
        assertTrue(tracker.observe(playing("a", isPlaying = false)).isEmpty())
        assertTrue(tracker.observe(playing("a", durationMs = 0)).isEmpty())
    }

    @Test
    fun aListenCountsAtHalfTheTrackAndOnlyOnce() {
        tracker.observe(playing("a"))
        assertTrue(tracker.observe(playing("a", positionMs = 99_000)).isEmpty())
        assertEquals(1, tracker.observe(playing("a", positionMs = 100_000)).filterIsInstance<ListenEvent.Counted>().size)
        assertTrue(tracker.observe(playing("a", positionMs = 150_000)).isEmpty())
    }

    @Test
    fun theThresholdCapsAtFourMinutesForLongTracks() {
        val long = 1_200_000L
        tracker.observe(playing("a", durationMs = long))
        assertTrue(tracker.observe(playing("a", positionMs = 239_000, durationMs = long)).isEmpty())
        assertEquals(1, tracker.observe(playing("a", positionMs = 240_000, durationMs = long)).filterIsInstance<ListenEvent.Counted>().size)
    }

    @Test
    fun tracksUnderThirtySecondsNeverCount() {
        tracker.observe(playing("a", durationMs = 29_000))
        assertTrue(tracker.observe(playing("a", positionMs = 28_000, durationMs = 29_000)).isEmpty())
    }

    @Test
    fun pausingAndResumingIsOneListen() {
        tracker.observe(playing("a"))
        assertTrue(tracker.observe(playing("a", positionMs = 5_000, isPlaying = false)).isEmpty())
        assertTrue(tracker.observe(playing("a", positionMs = 5_000)).isEmpty())
    }

    @Test
    fun seekingBackAfterItCountedDoesNotCountAgain() {
        tracker.observe(playing("a"))
        tracker.observe(playing("a", positionMs = 120_000))
        assertTrue(tracker.observe(playing("a", positionMs = 10_000)).isEmpty())
        assertTrue(tracker.observe(playing("a", positionMs = 120_000)).isEmpty())
    }

    /** #58: a song played on its own is always queue index 0, so the second single-song play must still be a listen. */
    @Test
    fun theSameSongPlayedAgainIsANewListen() {
        val first = tracker.observe(playing("a"))
        val counted1 = tracker.observe(playing("a", positionMs = 120_000))
        assertEquals(1, first.filterIsInstance<ListenEvent.Started>().size)
        assertEquals(1, counted1.filterIsInstance<ListenEvent.Counted>().size)

        tracker.beginPlay()
        val second = tracker.observe(playing("a", positionMs = 1_000))
        assertEquals(1, second.filterIsInstance<ListenEvent.Ended>().size, "the first listen ends when the new play begins")
        assertEquals(1, second.filterIsInstance<ListenEvent.Started>().size, "the second play is a new listen even at queue index 0")
        assertEquals(1, tracker.observe(playing("a", positionMs = 120_000)).filterIsInstance<ListenEvent.Counted>().size)
    }

    /** Clearing or shuffling the queue moves the playing track to a new index; that is not a new listen. */
    @Test
    fun theTrackMovingToAnotherQueueIndexIsNotANewListen() {
        val queue = listOf(track("x"), track("a"), track("y"))
        tracker.observe(playing("a", index = 1, queue = queue))
        tracker.observe(playing("a", index = 1, positionMs = 120_000, queue = queue))
        assertTrue(tracker.observe(playing("a", index = 0, positionMs = 121_000, queue = listOf(track("a")))).isEmpty())
    }

    @Test
    fun changingTrackEndsTheOldListenAtItsLastPositionAndStartsTheNext() {
        tracker.observe(playing("a", positionMs = 30_000))
        val events = tracker.observe(playing("b", positionMs = 500))
        val ended = events.filterIsInstance<ListenEvent.Ended>().single()
        assertEquals("a", ended.listen.trackId)
        assertEquals(30_000, ended.positionMs, "the position the old track was at, not the new track's")
        assertEquals("b", events.filterIsInstance<ListenEvent.Started>().single().listen.trackId)
    }

    @Test
    fun aListenEndsWhenNoTrackIsCurrentAnyMore() {
        tracker.observe(playing("a", positionMs = 7_000))
        val ended = tracker.observe(nothing).filterIsInstance<ListenEvent.Ended>().single()
        assertEquals(7_000, ended.positionMs)
    }

    @Test
    fun eachListenGetsItsOwnSessionId() {
        val a = tracker.observe(playing("a")).filterIsInstance<ListenEvent.Started>().single().listen.sessionId
        tracker.beginPlay()
        val b = tracker.observe(playing("a")).filterIsInstance<ListenEvent.Started>().single().listen.sessionId
        assertTrue(a != b)
    }
}
