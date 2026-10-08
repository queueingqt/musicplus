package com.musicplus.app.data.playback

import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdvanceTest {
    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 100, coverArtId = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private val queue = listOf("a", "b", "c", "d").map(::track)
    private val all: (Track) -> Boolean = { true }

    @Test
    fun theNextSongThatPlaysIsTheOneAfter() {
        assertEquals(Step.To(1), Advance.after(queue, 0, RepeatMode.OFF, all))
        assertEquals(Step.To(3), Advance.after(queue, 1, RepeatMode.OFF) { it.id != "c" }, "an unplayable song is passed over")
    }

    @Test
    fun theEndOfTheQueueIsTheEndUnlessRepeatAllCameRound() {
        assertNull(Advance.after(queue, 3, RepeatMode.OFF, all))
        assertNull(Advance.after(queue, 3, RepeatMode.REPEAT_TRACK, all))
        assertEquals(Step.WrapTo(0), Advance.after(queue, 3, RepeatMode.REPEAT_QUEUE, all))
    }

    @Test
    fun wrappingGoesToTheFirstSongThatPlaysNotJustTheFirstSong() {
        assertEquals(Step.WrapTo(1), Advance.after(queue, 3, RepeatMode.REPEAT_QUEUE) { it.id != "a" })
    }

    @Test
    fun aQueueWithNothingPlayableHasNoNextEvenUnderRepeatAll() {
        assertNull(Advance.after(queue, 1, RepeatMode.REPEAT_QUEUE) { false })
    }

    @Test
    fun theOnlyPlayableSongWrapsToItself() {
        assertEquals(Step.WrapTo(2), Advance.after(queue, 2, RepeatMode.REPEAT_QUEUE) { it.id == "c" })
    }

    @Test
    fun theSongBeforeMirrorsTheOneAfter() {
        assertEquals(Step.To(0), Advance.before(queue, 1, RepeatMode.OFF, all))
        assertEquals(Step.To(0), Advance.before(queue, 2, RepeatMode.OFF) { it.id != "b" })
        assertNull(Advance.before(queue, 0, RepeatMode.OFF, all))
        assertEquals(Step.WrapTo(3), Advance.before(queue, 0, RepeatMode.REPEAT_QUEUE, all))
        assertEquals(Step.WrapTo(2), Advance.before(queue, 0, RepeatMode.REPEAT_QUEUE) { it.id != "d" })
    }
}
