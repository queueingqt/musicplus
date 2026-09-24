package com.musicplus.app.data.playback

import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.musicplus.app.data.PlaybackStateRepository.Saved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackPersistenceTest {
    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 100, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun saved(index: Int = 0, shuffle: Boolean = false, repeat: RepeatMode = RepeatMode.OFF) =
        Saved(currentIndex = index, positionMs = 42_000, shuffle = shuffle, repeatMode = repeat)

    private fun restore(ids: List<String>, known: List<String>, saved: Saved) =
        PlaybackPersistence.restoredFrom(ids, known.associateWith(::track), saved)

    @Test
    fun theSavedOrderIsKeptAndSongsNoLongerInTheLibraryAreDropped() {
        val r = restore(listOf("a", "gone", "b", "c"), listOf("c", "a", "b"), saved(index = 2))
        assertEquals(listOf("a", "b", "c"), r!!.tracks.map { it.id })
    }

    @Test
    fun nothingRestoresWhenNoSavedSongIsLeft() {
        assertNull(restore(listOf("gone"), listOf("a"), saved()))
        assertNull(restore(emptyList(), listOf("a"), saved()))
    }

    @Test
    fun theIndexIsKeptInsideTheQueue() {
        assertEquals(1, restore(listOf("a", "b"), listOf("a", "b"), saved(index = 9))!!.index)
        assertEquals(0, restore(listOf("a", "b"), listOf("a", "b"), saved(index = -3))!!.index)
    }

    @Test
    fun shuffleWinsOverRepeatOneWhenBothWereSaved() {
        val r = restore(listOf("a"), listOf("a"), saved(shuffle = true, repeat = RepeatMode.REPEAT_TRACK))!!
        assertEquals(RepeatMode.OFF, r.repeatMode)
        assertEquals(true, r.shuffle)
    }

    @Test
    fun otherRepeatModesAreKeptWithShuffle() {
        assertEquals(RepeatMode.REPEAT_QUEUE, restore(listOf("a"), listOf("a"), saved(shuffle = true, repeat = RepeatMode.REPEAT_QUEUE))!!.repeatMode)
        assertEquals(RepeatMode.REPEAT_TRACK, restore(listOf("a"), listOf("a"), saved(repeat = RepeatMode.REPEAT_TRACK))!!.repeatMode)
    }

    @Test
    fun theResumePositionComesBack() {
        val r = restore(listOf("a"), listOf("a"), saved())!!
        assertEquals(42_000, r.positionMs)
    }
}
