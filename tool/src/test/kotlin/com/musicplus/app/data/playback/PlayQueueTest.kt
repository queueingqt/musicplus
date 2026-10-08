package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.musicplus.app.data.FetchGate
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue and the player under one owner, tested through the queue's interface with a fake player and fake sources. */
class PlayQueueTest {
    private class Harness(val scope: CoroutineScope, readyAfterMs: Long = 15) {
        val player = FakePlayer(scope, readyAfterMs = readyAfterMs)
        val sources = FakeSources()
        var playable: (Track) -> Boolean = { true }
        val notes = mutableListOf<String>()
        var newPlays = 0
        var checkpoints = 0
        val queue = PlayQueue(
            player, sources, scope,
            isPlayable = { playable(it) }, notify = { notes += it }, onNewPlay = { newPlays++ }, checkpoint = { checkpoints++ },
            timing = FAST,
        ).also { it.restoreFinished() }

        suspend fun until(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(2) }

        /** The start song is playing and the rest of the queue has been handed over and has settled. */
        suspend fun settled(songs: Int) {
            until { player.held.size == songs && queue.snapshot().isPlaying }
            delay(60)
        }
    }

    private fun run(readyAfterMs: Long = 15, body: suspend Harness.() -> Unit) = runBlocking<Unit> {
        val harness = Harness(CoroutineScope(coroutineContext + SupervisorJob()), readyAfterMs)
        try {
            harness.body()
        } finally {
            harness.scope.cancel()
        }
    }

    private fun track(n: Int, id: String = "a:$n", title: String = "t$n", albumId: String? = null) = Track(
        id = id, title = title, albumId = albumId, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 200, coverArtId = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private fun tracks(count: Int) = List(count) { track(it) }

    private val PlaybackState.titles get() = queue.map { it.title }

    // ---- playing ----

    @Test
    fun aSingleSongIsGivenToThePlayerAndPlays() = run {
        queue.play(tracks(1), 0)
        assertEquals(listOf("t0"), player.heldTitles)
        val s = queue.snapshot()
        assertTrue(s.isPlaying)
        assertFalse(s.isLoading)
        assertEquals(0, s.currentIndex)
        delay(80)
        assertEquals(1, player.count("setMediaQueue"), "a lone song that is a file is not handed over again")
    }

    @Test
    fun aLongerQueueStartsWithOneSongAndIsHandedOverWholeKeepingThePosition() = run {
        sources.queueLatencyMs = 40
        queue.play(tracks(5), 2)
        assertEquals(listOf("t2"), player.heldTitles, "only the starting song first, so audio begins at once")
        assertEquals(2, queue.snapshot().currentIndex, "the screen shows the queue's index, not the player's 0")
        player.playFor(5_000)
        settled(5)
        assertEquals(tracks(5).map { it.title }, player.heldTitles)
        assertTrue("seekTo(5000)" in player.calls, "the song is put back where it was: ${player.calls}")
        val s = queue.snapshot()
        assertEquals(2, s.currentIndex)
        assertEquals(5_000L, s.positionMs)
        assertTrue(s.isPlaying)
    }

    /** #47: the icon and the index must not move through the hand-over (the icon used to go PAUSE, PLAY, PAUSE, PLAY, PAUSE). */
    @Test
    fun nothingOnScreenMovesThroughTheHandOver() = run {
        sources.queueLatencyMs = 30
        val seen = mutableListOf<PlaybackState>()
        scope.launch { queue.state.collect { seen += it } }
        queue.play(tracks(5), 2)
        settled(5)
        val fromPlaying = seen.dropWhile { !it.isPlaying }
        assertTrue(fromPlaying.size > 3)
        assertTrue(fromPlaying.all { it.isPlaying }, "the play/pause icon never left playing")
        val once = seen.filter { it.queue.isNotEmpty() }
        assertTrue(once.all { it.currentIndex == 2 }, "the index never left the song that was tapped: ${once.map { it.currentIndex }.distinct()}")
    }

    @Test
    fun aStreamingStartHoldsBackTheQueueUntilAudioIsFlowing() = run(readyAfterMs = 10_000) {
        sources.streamStart = true
        queue.play(tracks(5), 0)
        delay(120)
        assertTrue(sources.queueCalls.isEmpty(), "fetching the queue beside a stream that has not started starved it for 95 s on the phone")
        until { sources.queueCalls.isNotEmpty() }
    }

    @Test
    fun aLoneSongThatStreamsIsSwappedOntoItsFile() = run {
        sources.streamStart = true
        queue.play(tracks(1), 0)
        assertFalse(queue.snapshot().canSeek, "a transcoded stream cannot seek")
        settled(1)
        until { player.swaps.size == 2 }
        delay(60)
        assertTrue(queue.snapshot().canSeek, "on its file it can")
    }

    @Test
    fun aSongThatCannotBePlayedIsSkippedForTheNextThatCan() = run {
        playable = { it.id != "a:0" }
        queue.play(tracks(3), 0)
        assertEquals(listOf("t1"), player.swaps.first(), "the first song that can be played is the one started")
        assertEquals(1, queue.snapshot().currentIndex)
        assertEquals(1, notes.size)
        assertTrue("Skipped" in notes.single())
    }

    @Test
    fun aFailedStartSaysWhyLeavesNothingLoadedAndPressingPlayTriesAgain() = run {
        sources.failStart = IOException("connection reset")
        queue.play(tracks(3), 0)
        val s = queue.snapshot()
        assertTrue(s.errorMessage!!.contains("Couldn't load"))
        assertFalse(s.isLoading)
        assertEquals(0, player.count("setMediaQueue"))
        sources.failStart = null
        queue.togglePlayPause()
        until { player.heldTitles == listOf("t0") }
        assertNull(queue.snapshot().errorMessage, "a new play clears the old failure")
    }

    @Test
    fun aNewPlayWhileTheFirstIsStillResolvingLeavesOnlyTheNewOne() = run {
        sources.startLatencyMs = 80
        queue.playAsync(tracks(3), 0)
        queue.playAsync(List(3) { track(it, id = "b:$it", title = "b$it") }, 1)
        settled(3)
        assertEquals(listOf("b1"), player.swaps.first(), "the first play never reached the player")
        assertTrue(player.swaps.flatten().all { it.startsWith("b") }, "nothing of the first play: ${player.swaps}")
        val s = queue.snapshot()
        assertEquals("b:1", s.currentTrack?.id)
        assertFalse(s.isLoading)
        assertEquals(2, newPlays)
    }

    // ---- a queue restored from disk ----

    private fun restored(count: Int, index: Int, positionMs: Long = 42_000) =
        RestoredPlayback(tracks(count), index, positionMs, shuffle = false, repeatMode = RepeatMode.OFF)

    /** Editing a queue nobody has started used to load all of it into the player, and to read the player's stale index 0 as the playing song. */
    @Test
    fun editingARestoredQueueDoesNotTouchThePlayerAndKeepsTheRestoredSong() = run {
        queue.restore(restored(5, index = 3))
        assertEquals(3, queue.snapshot().currentIndex)
        queue.removeAt(1)
        assertEquals(5, queue.snapshot().queue.size, "only upcoming songs can be removed")
        queue.removeAt(4)
        assertEquals(4, queue.snapshot().queue.size)
        assertEquals(3, queue.snapshot().currentIndex)
        queue.clear()
        assertEquals(listOf("t3"), queue.snapshot().titles)
        assertEquals(0, queue.snapshot().currentIndex)
        delay(60)
        assertEquals(emptyList(), player.calls.toList(), "the player was never touched")
        assertTrue(sources.queueCalls.isEmpty() && sources.startCalls.isEmpty(), "nothing was fetched")
        assertNull(queue.saveable())
    }

    @Test
    fun aRestoredQueueIsNotLoadingItIsWaitingForPlay() = run {
        queue.restore(restored(3, index = 1))
        val s = queue.snapshot()
        assertFalse(s.isLoading, "#63: it used to read as loading")
        assertFalse(s.isPlaying)
        assertEquals(0L, s.positionMs)
        assertNull(queue.albumArtHint.value, "a saved hint cannot say which song it was for, so none is restored (#77)")
    }

    @Test
    fun pressingPlayOnARestoredQueuePlaysTheRestoredSongAndSeeksToTheSavedPosition() = run {
        queue.restore(restored(4, index = 2, positionMs = 42_000))
        queue.togglePlayPause()
        until { "seekTo(42000)" in player.calls }
        settled(4)
        assertEquals(listOf("t2"), player.swaps.first(), "the restored song is the one that starts")
        assertEquals(42_000L, queue.snapshot().positionMs)
        assertEquals(2, queue.snapshot().currentIndex)
    }

    // ---- editing a queue that is playing ----

    @Test
    fun addingSongsHandsOverTheLongerQueueWithoutMovingTheSongThatIsPlaying() = run {
        queue.play(tracks(3), 1)
        settled(3)
        player.playFor(20_000)
        queue.addToQueue(listOf(track(3), track(4)))
        until { player.held.size == 5 }
        delay(60)
        assertEquals(tracks(5).map { it.title }, player.heldTitles)
        assertTrue("seekTo(20000)" in player.calls)
        val s = queue.snapshot()
        assertEquals(1, s.currentIndex)
        assertEquals(20_000L, s.positionMs)
        assertTrue(s.isPlaying)
    }

    /** The position used to be read before the queue was resolved, so a slow resolve replayed the stretch that had played meanwhile. */
    @Test
    fun thePositionIsReadAfterTheResolveNotBefore() = run {
        queue.play(tracks(5), 0)
        settled(5)
        sources.queueLatencyMs = 100
        player.playFor(1_000)
        queue.removeAt(3)
        delay(40)
        player.playFor(30_000)
        until { player.swaps.size == 3 }
        delay(60)
        assertTrue("seekTo(31000)" in player.calls, "put back at where it is now, not where it was: ${player.calls}")
    }

    @Test
    fun aRunOfEditsEndsAsOneHandOverWithTheFinalQueue() = run {
        queue.play(tracks(6), 0)
        settled(6)
        sources.queueLatencyMs = 50
        queue.removeAt(5)
        queue.removeAt(4)
        queue.move(1, 1)
        until { player.swaps.size == 3 }
        delay(150)
        assertEquals(3, player.swaps.size, "start, the first full hand-over, and ONE for all three edits")
        assertEquals(queue.snapshot().titles, player.heldTitles, "the player holds exactly the queue that is shown")
        assertEquals(4, player.held.size)
        assertEquals(listOf("t0", "t2", "t1", "t3"), player.heldTitles)
    }

    @Test
    fun anEditWhileTheRestOfTheQueueIsStillBeingFetchedReplacesThatHandOver() = run {
        sources.queueLatencyMs = 80
        queue.play(tracks(5), 0)
        queue.removeAt(4)
        until { player.swaps.size == 2 }
        delay(150)
        assertEquals(2, player.swaps.size, "the superseded hand-over never reached the player")
        assertEquals(4, player.held.size)
    }

    @Test
    fun aQueueThatCannotBeFetchedIsPutBackAsItWas() = run {
        queue.play(tracks(4), 0)
        settled(4)
        sources.failQueue = IOException("no route")
        queue.removeAt(2)
        until { queue.snapshot().errorMessage != null }
        assertEquals(4, queue.snapshot().queue.size, "the queue that is shown is the one the player holds")
        assertTrue(queue.snapshot().errorMessage!!.contains("Couldn't update the queue"))
    }

    @Test
    fun clearingKeepsOnlyTheSongThatIsPlaying() = run {
        queue.play(tracks(5), 2)
        settled(5)
        queue.clear()
        until { player.held.size == 1 }
        delay(60)
        assertEquals(listOf("t2"), queue.snapshot().titles)
        assertEquals(0, queue.snapshot().currentIndex)
        assertTrue(queue.snapshot().isPlaying)
    }

    @Test
    fun removingAndMovingOnlyTouchSongsAfterTheOneThatIsPlaying() = run {
        queue.play(tracks(5), 2)
        settled(5)
        val swaps = player.swaps.size
        queue.removeAt(2)
        queue.removeAt(1)
        queue.move(1, 1)
        queue.move(3, -1) // 3 -> 2, which is the playing song
        delay(100)
        assertEquals(swaps, player.swaps.size)
        assertEquals(tracks(5).map { it.title }, queue.snapshot().titles)
    }

    @Test
    fun aFavoriteIsPatchedInTheQueueWithoutTouchingThePlayer() = run {
        queue.play(tracks(3), 0)
        settled(3)
        val before = player.calls.size
        queue.updateFavorite("a:1", true)
        assertTrue(queue.snapshot().queue[1].isFavorite)
        assertEquals(before, player.calls.size)
    }

    @Test
    fun aPersonsPauseDuringAHandOverIsNotResumedOver() = run {
        queue.play(tracks(4), 0)
        settled(4)
        sources.queueLatencyMs = 30
        val swaps = player.swaps.size
        queue.removeAt(3)
        until { player.swaps.size == swaps + 1 }
        queue.togglePlayPause()
        delay(200)
        assertFalse(queue.snapshot().isPlaying)
        assertTrue(player.calls.lastIndexOf("pause") > player.calls.lastIndexOf("play"), "no play after the pause: ${player.calls}")
    }

    // ---- shuffle and repeat ----

    @Test
    fun shufflingPutsThePlayingSongFirstAndTurningItOffRestoresTheOrder() = run {
        queue.play(tracks(6), 3)
        settled(6)
        queue.setShuffle(true)
        until { player.swaps.size == 3 }
        delay(80)
        val shuffled = queue.snapshot()
        assertEquals("t3", shuffled.queue.first().title)
        assertEquals(0, shuffled.currentIndex)
        assertEquals(tracks(6).map { it.title }.toSet(), shuffled.titles.toSet())
        assertEquals(shuffled.titles, player.heldTitles)

        queue.setShuffle(false)
        until { player.swaps.size == 4 }
        delay(80)
        assertEquals(tracks(6).map { it.title }, queue.snapshot().titles)
        assertEquals(3, queue.snapshot().currentIndex)
        assertTrue(queue.snapshot().isPlaying)
    }

    @Test
    fun repeatOneAndShuffleExcludeEachOther() = run {
        queue.play(tracks(3), 0)
        settled(3)
        queue.setRepeatMode(RepeatMode.REPEAT_TRACK)
        queue.setShuffle(true)
        assertEquals(RepeatMode.OFF, queue.snapshot().repeatMode)
        assertTrue(queue.snapshot().shuffle)
        queue.setRepeatMode(RepeatMode.REPEAT_TRACK)
        assertFalse(queue.snapshot().shuffle)
        assertEquals(RepeatMode.REPEAT_TRACK, queue.snapshot().repeatMode)
    }

    // ---- moving between songs ----

    @Test
    fun nextOnTheLastSongWrapsToTheStartUnderRepeatAll() = run {
        queue.setRepeatMode(RepeatMode.REPEAT_QUEUE)
        queue.play(tracks(3), 2)
        settled(3)
        queue.skipToNext()
        until { player.swaps.size == 3 }
        assertEquals(listOf("t0"), player.swaps[2], "started again from the first song")
        assertEquals(0, player.count("skipToNext"))
    }

    @Test
    fun previousOnTheFirstSongWrapsToTheLastUnderRepeatAll() = run {
        queue.setRepeatMode(RepeatMode.REPEAT_QUEUE)
        queue.play(tracks(3), 0)
        settled(3)
        queue.skipToPrevious()
        until { player.swaps.size == 3 }
        assertEquals(listOf("t2"), player.swaps[2])
    }

    @Test
    fun nextAndPreviousInTheMiddleAreThePlayersOwn() = run {
        queue.play(tracks(3), 1)
        settled(3)
        queue.skipToNext()
        assertEquals(1, player.count("skipToNext"))
        queue.skipToPrevious()
        assertEquals(1, player.count("skipToPrevious"))
    }

    /** While only the starting song is in the player its own skip has nothing to skip to (a minute or more on a weak link): "next" did nothing. */
    @Test
    fun nextWhileOnlyTheStartingSongIsLoadedPlaysTheNextSong() = run {
        sources.queueLatencyMs = 300
        queue.play(tracks(5), 0)
        queue.skipToNext()
        until { player.swaps.size >= 2 }
        assertEquals(listOf("t1"), player.swaps[1])
        assertEquals(1, queue.snapshot().currentIndex)
        assertEquals(0, player.count("skipToNext"))
        sources.queueLatencyMs = 10
        settled(5)
        assertEquals(1, queue.snapshot().currentIndex)
    }

    @Test
    fun previousWhileOnlyTheStartingSongIsLoadedGoesBackOrRestartsWhenWellIn() = run {
        sources.queueLatencyMs = 400
        queue.play(tracks(5), 2)
        queue.skipToPrevious()
        until { player.swaps.size >= 2 }
        assertEquals(listOf("t1"), player.swaps[1])

        queue.play(tracks(5), 3)
        player.playFor(5_000)
        val swaps = player.swaps.size
        queue.skipToPrevious()
        assertTrue("seekTo(0)" in player.calls)
        assertEquals(swaps, player.swaps.size, "well into the song, previous restarts it")
    }

    @Test
    fun pressingPlayAfterAPlayerErrorStartsTheSongAgain() = run {
        queue.play(tracks(3), 1)
        settled(3)
        player.fail(LightAudioError(LightAudioErrorKind.Source, "ERROR_CODE_IO_UNSPECIFIED", 1))
        val swaps = player.swaps.size
        queue.togglePlayPause()
        until { player.swaps.size > swaps }
        assertEquals("t1", player.swaps[swaps].first())
    }

    // ---- saving ----

    @Test
    fun theQueueIsOnlySaveableOnceThePlayerHoldsIt() = run {
        assertNull(queue.saveable())
        sources.queueLatencyMs = 60
        queue.play(tracks(4), 2)
        val early = assertNotNull(queue.saveable())
        assertEquals(2, early.currentIndex, "the queue's index, not the player's 0")
        settled(4)
        player.playFor(7_000)
        val saved = assertNotNull(queue.saveable())
        assertEquals(2, saved.currentIndex)
        assertEquals(7_000L, saved.positionMs)
    }

    @Test
    fun priorityFollowsTheSongThatIsPlaying() = run {
        queue.play(tracks(8), 1)
        settled(8)
        assertEquals(FetchGate.Priority.NOW_PLAYING, queue.priorityForSong("a:1"))
        assertEquals(FetchGate.Priority.UP_NEXT, queue.priorityForSong("a:2"))
        assertEquals(FetchGate.Priority.UP_NEXT, queue.priorityForSong("a:4"))
        assertEquals(FetchGate.Priority.QUEUE, queue.priorityForSong("a:5"))
        assertEquals(FetchGate.Priority.QUEUE, queue.priorityForSong("a:0"))
        assertNull(queue.priorityForSong("a:99"))
    }

    // ---- resetting (#64) ----

    /** A hand-over documented as 30 to 100 s could bring the queue back after "Clear all local data". */
    @Test
    fun aResetWhileTheQueueIsBeingHandedOverBringsNothingBack() = run {
        sources.queueLatencyMs = 80
        queue.play(tracks(5), 0)
        queue.reset()
        delay(300)
        assertEquals(1, player.swaps.size, "only the starting song ever reached the player")
        val s = queue.snapshot()
        assertTrue(s.queue.isEmpty())
        assertFalse(s.isPlaying)
        assertNull(queue.saveable())
    }

    @Test
    fun aResetClearsEveryFlag() = run {
        sources.streamStart = true
        queue.play(tracks(3), 0)
        assertFalse(queue.snapshot().canSeek)
        queue.setShuffle(true)
        queue.noteFailure("boom")
        queue.reset()
        // An empty queue has no song to be on: index 0, as the screens have always been given.
        assertEquals(PlaybackState().copy(currentIndex = 0), queue.snapshot())
        assertNull(queue.albumArtHint.value)
    }

    @Test
    fun aResetDuringALoadLeavesItNotLoading() = run {
        sources.startLatencyMs = 100
        queue.playAsync(tracks(3), 0)
        assertTrue(queue.snapshot().isLoading, "loading from the moment the play begins")
        queue.reset()
        delay(200)
        assertFalse(queue.snapshot().isLoading)
        assertEquals(0, player.count("setMediaQueue"))
    }

    // ---- the album-art hint belongs to its album (#77) ----

    private fun mixed() = listOf(track(0, albumId = "alb1"), track(1, albumId = "alb1"), track(2, albumId = "alb2"))

    @Test
    fun aHintAppliesToSongsOfItsAlbumAndNoOther() {
        val hint = AlbumArtHint("http://art/alb1", albumId = "alb1")
        assertEquals("http://art/alb1", hint.coverArtIdFor(track(0, albumId = "alb1")))
        assertNull(hint.coverArtIdFor(track(2, albumId = "alb2")))
        assertNull(hint.coverArtIdFor(null))
        assertNull(AlbumArtHint("http://art/x", albumId = null).coverArtIdFor(track(0, albumId = null)), "a hint for no album applies to nothing")
    }

    @Test
    fun theHintGivenToAPlayIsForTheAlbumOfTheSongItStarts() = run {
        queue.play(mixed(), 0, albumArtId = "http://art/alb1")
        assertEquals(AlbumArtHint("http://art/alb1", "alb1"), queue.albumArtHint.value)
    }

    @Test
    fun aReplayInTheSameAlbumKeepsTheHintAndOneInAnotherAlbumDropsIt() = run {
        queue.play(mixed(), 0, albumArtId = "http://art/alb1")
        settled(3)
        queue.jumpToAsync(1)
        delay(80)
        assertEquals("http://art/alb1", queue.albumArtHint.value?.coverArtId, "same album: the flash-free art is still right")
        queue.jumpToAsync(2)
        delay(80)
        assertNull(queue.albumArtHint.value, "another album: the hint would show the wrong art")
    }

    @Test
    fun aNewExplicitHintReplacesTheOldOne() = run {
        queue.play(mixed(), 0, albumArtId = "http://art/alb1")
        queue.play(mixed(), 2, albumArtId = "http://art/alb2")
        assertEquals(AlbumArtHint("http://art/alb2", "alb2"), queue.albumArtHint.value)
    }

    // ---- one projection (#63) ----

    /** The synchronous read and the live flow are the same function of the same inputs, so no situation can make them disagree. */
    @Test
    fun theSnapshotAndTheLiveStateAgreeInEverySituation() = run {
        suspend fun agree(situation: String) = assertEquals(queue.snapshot(), queue.state.first(), situation)

        agree("nothing")
        queue.restore(restored(4, index = 2))
        agree("restored, waiting for play")
        sources.startLatencyMs = 60
        queue.playAsync(tracks(4), 1)
        assertTrue(queue.snapshot().isLoading)
        agree("loading")
        settled(4)
        agree("playing")
        queue.togglePlayPause()
        delay(20)
        agree("paused")
        queue.togglePlayPause()
        sources.queueLatencyMs = 20
        queue.removeAt(3)
        until { player.swaps.size == 3 }
        agree("during a hand-over")
    }

    private companion object {
        /** Production timings scaled down so a hand-over takes tens of milliseconds, not seconds. */
        val FAST = PlayQueue.Timing(
            startPlaybackWaitMs = 200, streamStartWaitMs = 250, swapIndexWaitMs = 100, durationWaitMs = 300,
            handOverResumeWaitMs = 100, handOverSettleMs = 10, restoreWaitMs = 100,
        )
    }
}
