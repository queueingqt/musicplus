package com.musicplus.app.data.playback

import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * The player, as far as the queue is concerned: what it is doing (live values) and the few things it can be told. The SDK's
 * [LightAudioPlayer] is a final class with an internal constructor, so it cannot be faked or subclassed; this is the seam where a
 * test's fake player and the real one meet ([LightQueuePlayer]).
 *
 * Behaviours of the real player that everything above this depends on, each confirmed on the phone:
 * - [setMediaQueue] replaces the whole list; there is no incremental append or jump. It resets [durationMs] to 0 at once (the new
 *   item has not been probed), [positionMs] to 0, and makes [isPlaying] drop out and come back over about 0.8 s.
 * - [seekTo] clamps to the duration and an unknown duration is 0, so a seek made before the new item's duration is known lands at 0.
 * - [currentMediaItemIndex] is an index into the list the player holds, not into the app's queue.
 */
interface QueuePlayer {
    val currentMediaItemIndex: StateFlow<Int>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val error: StateFlow<LightAudioError?>

    suspend fun awaitReady(): Boolean

    fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun skipBack()
    fun skipForward()
    fun skipToNext()
    fun skipToPrevious()
    fun release()
}

/** The SDK's player behind [QueuePlayer]: every call is passed straight through. */
class LightQueuePlayer(private val player: LightAudioPlayer) : QueuePlayer {
    override val currentMediaItemIndex get() = player.currentMediaItemIndex
    override val isPlaying get() = player.isPlaying
    override val positionMs get() = player.positionMs
    override val durationMs get() = player.durationMs
    override val error get() = player.error

    override suspend fun awaitReady() = player.awaitReady()
    override fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int) = player.setMediaQueue(items, startIndex)
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) = player.seekTo(ms)
    override fun skipBack() = player.skipBack()
    override fun skipForward() = player.skipForward()
    override fun skipToNext() = player.skipToNext()
    override fun skipToPrevious() = player.skipToPrevious()
    override fun release() = player.release()
}
