package com.musicplus.app.data.playback

import com.musicplus.app.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue's edit rules on plain lists: no player, no timing, no hand-over. */
class QueueEditsTest {
    private fun track(id: String) = Track(
        id = id, title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 100, coverArtId = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun queue(vararg ids: String) = ids.map(::track)
    private fun List<Track>.ids() = map { it.id }

    private val abcde = queue("s:a", "s:b", "s:c", "s:d", "s:e")

    @Test
    fun appendAddsAfterTheQueueAndAnEmptyAppendIsNothing() {
        assertEquals(listOf("s:a", "s:b", "s:x"), QueueEdits.append(queue("s:a", "s:b"), queue("s:x"))!!.queue.ids())
        assertNull(QueueEdits.append(queue("s:a"), emptyList()))
    }

    @Test
    fun clearKeepsOnlyWhatIsPlaying() {
        assertEquals(listOf("s:c"), QueueEdits.clear(abcde, playing = 2)!!.queue.ids())
        assertNull(QueueEdits.clear(emptyList(), playing = 0))
        assertEquals(listOf("s:e"), QueueEdits.clear(abcde, playing = 99)!!.queue.ids(), "a stale index is clamped, not trusted")
    }

    @Test
    fun onlyAnUpcomingSongCanBeRemoved() {
        assertEquals(listOf("s:a", "s:b", "s:c", "s:e"), QueueEdits.removeAt(abcde, playing = 1, index = 3)!!.queue.ids())
        assertNull(QueueEdits.removeAt(abcde, playing = 2, index = 2), "the playing song is not removed: that would be a skip")
        assertNull(QueueEdits.removeAt(abcde, playing = 2, index = 0), "nor a song already played")
        assertNull(QueueEdits.removeAt(abcde, playing = 2, index = 9))
    }

    @Test
    fun aRemovedServersUpcomingSongsGoButThePlayingOneStays() {
        val mixed = queue("a:1", "b:2", "a:3", "b:4", "a:5")
        // Playing "b:2" (index 1): server b's song after it goes, its own playing song stays, server a's are untouched.
        assertEquals(listOf("a:1", "b:2", "a:3", "a:5"), QueueEdits.removeServer(mixed, playing = 1, serverId = "b")!!.queue.ids())
        assertNull(QueueEdits.removeServer(abcde, playing = 0, serverId = "nobody"), "nothing to remove is not an edit")
    }

    @Test
    fun anUpcomingSongMovesWithinTheUpcomingOnes() {
        assertEquals(listOf("s:a", "s:b", "s:d", "s:c", "s:e"), QueueEdits.move(abcde, playing = 1, index = 2, delta = 1)!!.queue.ids())
        assertNull(QueueEdits.move(abcde, playing = 2, index = 3, delta = -1), "it would land on the playing song")
        assertNull(QueueEdits.move(abcde, playing = 2, index = 2, delta = 1), "the playing song is not moved")
        assertNull(QueueEdits.move(abcde, playing = 0, index = 4, delta = 1), "off the end")
    }

    @Test
    fun shuffleKeepsThePlayingSongFirstAndRemembersTheOrder() {
        val shuffled = QueueEdits.shuffle(abcde, playing = 2, random = Random(7))!!
        assertEquals("s:c", shuffled.edit.queue.first().id)
        assertEquals(0, shuffled.edit.playingIndex)
        assertEquals(abcde.ids().sorted(), shuffled.edit.queue.ids().sorted(), "every song is still there, once")
        assertEquals(abcde.ids(), shuffled.originalOrder)
        assertNull(QueueEdits.shuffle(queue("s:a"), playing = 0), "one song: nothing to shuffle")
    }

    @Test
    fun unshuffleRestoresTheOrderWithThePlayingSongWhereverItFalls() {
        val shuffled = QueueEdits.shuffle(abcde, playing = 2, random = Random(7))!!
        val back = QueueEdits.unshuffle(shuffled.edit.queue, playing = 0, originalOrder = shuffled.originalOrder)!!
        assertEquals(abcde.ids(), back.queue.ids())
        assertEquals(2, back.playingIndex, "the song that was playing is found where it was")
    }

    @Test
    fun unshuffleAppendsASongAddedWhileShuffledAndDropsOneRemoved() {
        val original = listOf("s:a", "s:b", "s:c", "s:d")
        // While shuffled: "s:b" was removed, "s:x" was added.
        val whileShuffled = queue("s:c", "s:a", "s:d", "s:x")
        val back = QueueEdits.unshuffle(whileShuffled, playing = 0, originalOrder = original)!!
        assertEquals(listOf("s:a", "s:c", "s:d", "s:x"), back.queue.ids())
        assertEquals(1, back.playingIndex)
    }

    @Test
    fun editsThatChangeNothingAreNull() {
        assertNotNull(QueueEdits.append(abcde, queue("s:z")))
        assertNull(QueueEdits.unshuffle(queue("s:a"), playing = 0, originalOrder = listOf("s:a")))
        assertTrue(QueueEdits.unshuffle(emptyList(), 0, emptyList()) == null)
    }
}
