package com.musicplus.app.data.playback

import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Player error, then retry or skip, through the real queue with a fake player: the glue that used to be untestable inside PlaybackRepository. */
class PlayerErrorHandlerTest {
    private class Harness(val scope: CoroutineScope) {
        val player = FakePlayer(scope)
        var playable: (Track) -> Boolean = { true }
        val notes = mutableListOf<String>()
        val unreachable = mutableListOf<String>()
        val queue = PlayQueue(
            player, FakeSources(), scope, isPlayable = { playable(it) }, notify = {}, onNewPlay = {}, checkpoint = {},
            timing = PlayQueue.Timing(
                startPlaybackWaitMs = 200, streamStartWaitMs = 250, swapIndexWaitMs = 100, durationWaitMs = 300,
                handOverResumeWaitMs = 100, handOverSettleMs = 10, restoreWaitMs = 100,
            ),
        ).also { it.restoreFinished() }
        val handler = PlayerErrorHandler(
            scope, queue, isPlayable = { playable(it) }, onServerUnreachable = { unreachable += it }, notify = { notes += it }, retryDelayMs = 40,
        ).also { it.start() }

        suspend fun until(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(2) }

        suspend fun settled(songs: Int) {
            until { player.held.size == songs && queue.snapshot().isPlaying }
            delay(60)
        }

        fun failSource(diagnostic: String = "ERROR_CODE_IO_UNSPECIFIED") =
            player.fail(LightAudioError(LightAudioErrorKind.Source, diagnostic, player.currentMediaItemIndex.value))
    }

    private fun run(body: suspend Harness.() -> Unit) = runBlocking<Unit> {
        val harness = Harness(CoroutineScope(coroutineContext + SupervisorJob()))
        try { harness.body() } finally { harness.scope.cancel() }
    }

    private fun track(n: Int) = Track(
        id = "s:$n", title = "t$n", albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 200, coverArtId = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private val songs = List(4, ::track)

    @Test
    fun aFailureAtTheStartOfASongPlaysItAgainOnce() = run {
        queue.play(songs, 1)
        settled(4)
        val before = player.count("setMediaQueue")
        failSource()
        until { player.count("setMediaQueue") > before }
        assertEquals(1, queue.snapshot().currentIndex)
    }

    @Test
    fun aJumpMadeDuringTheRetryDelayIsNotOverwrittenByTheOldQueue() = run {
        queue.play(songs, 1)
        settled(4)
        failSource()
        queue.jumpToAsync(3) // the person moved on before the 40 ms retry came due
        until { queue.snapshot().currentIndex == 3 }
        delay(200)
        assertEquals(3, queue.snapshot().currentIndex, "the retry saw that the song had changed and left it alone")
    }

    @Test
    fun anUnplayableSongIsSkippedToTheNextPlayableOneWithANote() = run {
        queue.play(songs, 0)
        settled(4)
        playable = { it.id != "s:0" && it.id != "s:1" }
        player.fail(LightAudioError(LightAudioErrorKind.Source, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 0))
        until { queue.snapshot().currentIndex == 2 }
        assertEquals(listOf("Skipped \"t0\": server not reachable"), notes)
        assertEquals(listOf("s"), unreachable, "a network failure at the start says the server cannot be reached")
    }

    @Test
    fun underRepeatAllTheSkipWrapsToTheFirstSongThatPlays() = run {
        queue.play(songs, 3)
        settled(4)
        queue.setRepeatMode(RepeatMode.REPEAT_QUEUE)
        playable = { it.id != "s:3" && it.id != "s:0" }
        failSource()
        until { queue.snapshot().currentIndex == 1 }
        assertTrue(notes.single().startsWith("Skipped \"t3\""))
    }

    @Test
    fun withNothingLeftThatPlaysItStopsAndSaysSo() = run {
        queue.play(songs, 1)
        settled(4)
        playable = { false }
        failSource()
        until { notes.isNotEmpty() }
        assertEquals(listOf("Server not reachable"), notes)
        assertTrue(!queue.snapshot().isPlaying)
    }
}
