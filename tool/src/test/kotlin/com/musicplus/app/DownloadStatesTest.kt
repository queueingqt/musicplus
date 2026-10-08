package com.musicplus.app

import com.musicplus.app.data.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The download state table: what a tap on a song's control does for each status, what the control says, and how a list's songs add up. */
class DownloadStatesTest {
    @Test
    fun aTapStartsADownloadOnlyWhereThereIsNoneOrItFailed() {
        assertTrue(downloadTapStarts(null))
        assertTrue(downloadTapStarts(DownloadStatus.FAILED))
        assertFalse(downloadTapStarts(DownloadStatus.QUEUED))
        assertFalse(downloadTapStarts(DownloadStatus.DOWNLOADING))
        assertFalse(downloadTapStarts(DownloadStatus.COMPLETE))
    }

    @Test
    fun afterATapTheControlShowsQueuedOrNothing() {
        assertEquals(DownloadStatus.QUEUED, statusAfterDownloadTap(null))
        assertEquals(DownloadStatus.QUEUED, statusAfterDownloadTap(DownloadStatus.FAILED))
        for (stopped in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETE)) {
            assertNull(statusAfterDownloadTap(stopped), "$stopped stops or removes")
        }
    }

    @Test
    fun everyLabelSaysWhatATapWillDo() {
        assertEquals("Download", downloadStatusLabel(null))
        assertTrue("cancel" in downloadStatusLabel(DownloadStatus.QUEUED))
        assertTrue("cancel" in downloadStatusLabel(DownloadStatus.DOWNLOADING))
        assertTrue("remove" in downloadStatusLabel(DownloadStatus.COMPLETE))
        assertTrue("retry" in downloadStatusLabel(DownloadStatus.FAILED))
        for (status in DownloadStatus.values()) {
            val startsAgain = downloadTapStarts(status)
            val label = downloadStatusLabel(status)
            assertEquals(startsAgain, "retry" in label || label == "Download", "$status: label and tap agree")
        }
    }

    @Test
    fun onlyQueuedAndDownloadingAreInProgress() {
        assertTrue(DownloadStatus.QUEUED.isInProgress())
        assertTrue(DownloadStatus.DOWNLOADING.isInProgress())
        assertFalse(DownloadStatus.COMPLETE.isInProgress())
        assertFalse(DownloadStatus.FAILED.isInProgress())
        assertFalse(null.isInProgress())
    }

    private fun state(vararg statuses: DownloadStatus?): TrackListDownloadState {
        val ids = statuses.indices.map { "s$it" }
        return trackListDownloadState(ids, ids.zip(statuses).mapNotNull { (id, s) -> s?.let { id to it } }.toMap())
    }

    @Test
    fun anEmptyListIsNone() {
        assertEquals(TrackListDownloadState.NONE, trackListDownloadState(emptyList(), emptyMap()))
    }

    @Test
    fun everySongDownloadedIsAll() {
        assertEquals(TrackListDownloadState.ALL, state(DownloadStatus.COMPLETE, DownloadStatus.COMPLETE))
    }

    @Test
    fun aMixThatHasSettledIsSomeAndNothingDownloadedIsNone() {
        assertEquals(TrackListDownloadState.SOME, state(DownloadStatus.COMPLETE, null))
        assertEquals(TrackListDownloadState.SOME, state(DownloadStatus.COMPLETE, DownloadStatus.FAILED))
        assertEquals(TrackListDownloadState.NONE, state(null, null))
        assertEquals(TrackListDownloadState.NONE, state(DownloadStatus.FAILED, null))
    }

    @Test
    fun anythingStillRunningMakesTheListInProgressEvenIfMostAreDone() {
        assertEquals(TrackListDownloadState.IN_PROGRESS, state(DownloadStatus.QUEUED, null))
        assertEquals(TrackListDownloadState.IN_PROGRESS, state(DownloadStatus.COMPLETE, DownloadStatus.DOWNLOADING))
        assertEquals(TrackListDownloadState.IN_PROGRESS, state(DownloadStatus.DOWNLOADING))
    }

    @Test
    fun aSongOfTheListWithNoDownloadRowCountsAsNotDownloaded() {
        assertEquals(TrackListDownloadState.SOME, trackListDownloadState(listOf("a", "b"), mapOf("a" to DownloadStatus.COMPLETE)))
        assertEquals(TrackListDownloadState.NONE, trackListDownloadState(listOf("a"), mapOf("zzz" to DownloadStatus.COMPLETE)))
    }

    @Test
    fun aListsDownloadRowReadsTheSameWhicheverListItIsOn() {
        assertEquals("Download album", trackListDownloadLabel("album", TrackListDownloadState.NONE))
        assertEquals("Download playlist", trackListDownloadLabel("playlist", TrackListDownloadState.NONE))
        assertEquals("Some tracks downloaded — download the rest", trackListDownloadLabel("album", TrackListDownloadState.SOME))
        assertEquals("Downloading — tap to cancel", trackListDownloadLabel("album", TrackListDownloadState.IN_PROGRESS))
        assertEquals("Downloaded — remove", trackListDownloadLabel("album", TrackListDownloadState.ALL))
        assertEquals(downloadStatusIcon(DownloadStatus.COMPLETE), trackListDownloadIcon(TrackListDownloadState.ALL))
        assertEquals(downloadStatusIcon(DownloadStatus.DOWNLOADING), trackListDownloadIcon(TrackListDownloadState.IN_PROGRESS))
        assertEquals(downloadStatusIcon(null), trackListDownloadIcon(TrackListDownloadState.SOME))
    }

    @Test
    fun aListRowRebuildsItselfFromWhatTheToggleReturns() = kotlinx.coroutines.runBlocking<Unit> {
        val row = trackListDownloadActionItem("album", TrackListDownloadState.NONE) { TrackListDownloadState.IN_PROGRESS }
        val next = (row.onSelect as ActionMenuSelection.Perform).action()!!
        assertEquals("Downloading — tap to cancel", next.label)
        assertEquals(row.key, next.key)
    }

    @Test
    fun aSongsRowFollowsWhatTheTapReturns() = kotlinx.coroutines.runBlocking<Unit> {
        val row = trackDownloadActionItem(null) { shown -> statusAfterDownloadTap(shown) }
        assertEquals("Download", row.label)
        val queued = (row.onSelect as ActionMenuSelection.Perform).action()!!
        assertEquals(downloadStatusLabel(DownloadStatus.QUEUED), queued.label)
        val stopped = (queued.onSelect as ActionMenuSelection.Perform).action()!!
        assertEquals("Download", stopped.label, "a second tap stops it")
    }

    @Test
    fun theWholeArtistDownloadSaysHowManyAlbumsItCovers() {
        assertEquals("Downloads every track across 1 album to this device.", downloadEntireArtistMessage(1))
        assertEquals("Downloads every track across 7 albums to this device.", downloadEntireArtistMessage(7))
    }
}
