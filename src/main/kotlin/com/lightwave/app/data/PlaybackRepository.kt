package com.lightwave.app.data

import com.lightwave.app.PlaybackState
import com.lightwave.app.RepeatMode
import com.lightwave.app.Track
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File

/**
 * Wraps one [LightAudio]-provided player for the whole tool session. Built as
 * `LightAudioPlayback.Detached` — per the SDK reference notes, that's what
 * survives navigating away from PlayerScreen or the phone locking, and only one
 * detached handle may exist at a time, so this MUST be a single shared instance
 * (see [PlaybackRepositoryHolder]), never constructed fresh per screen.
 */
class PlaybackRepository(
    audio: LightAudio,
    private val apiHolder: SubsonicApiHolder,
) {
    private val player = audio.newPlayer(playback = LightAudioPlayback.Detached)

    private val queue = MutableStateFlow<List<Track>>(emptyList())
    private val shuffle = MutableStateFlow(false)
    private val repeatMode = MutableStateFlow(RepeatMode.OFF)

    // Nested 4-way combines rather than one 7-way call — kotlinx.coroutines only has
    // typed `combine` overloads up to 5 flows; this keeps every step on solid ground
    // instead of reaching for the untyped Array<T> vararg overload.
    private data class PlayerCoreState(val index: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long)

    private val playerCore = combine(
        player.currentMediaItemIndex, player.isPlaying, player.positionMs, player.durationMs,
    ) { index, isPlaying, positionMs, durationMs -> PlayerCoreState(index, isPlaying, positionMs, durationMs) }

    val state = combine(queue, playerCore, shuffle, repeatMode) { q, core, isShuffle, mode ->
        PlaybackState(
            queue = q,
            currentIndex = core.index,
            isPlaying = core.isPlaying,
            positionMs = core.positionMs,
            durationMs = core.durationMs,
            shuffle = isShuffle,
            repeatMode = mode,
        )
    }

    suspend fun play(tracks: List<Track>, startIndex: Int) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return // not configured — nothing playable
        queue.value = tracks
        player.setMediaQueue(tracks.map { it.toAudioItem(api) }, startIndex)
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying.value) player.pause() else player.play()
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()
    fun skipToNext() = player.skipToNext()
    fun skipToPrevious() = player.skipToPrevious()
    fun seekTo(ms: Long) = player.seekTo(ms)

    fun setShuffle(enabled: Boolean) {
        shuffle.value = enabled
        // TODO: this only flips the flag for the UI toggle state — it doesn't yet
        // reshuffle the live queue or restore original order on disable. Wire that
        // up once the queue-reordering UX is decided (reshuffle-in-place vs.
        // shuffle-from-current-track-forward).
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode.value = mode
        // TODO: LightAudioPlayer's confirmed public surface (see SDK reference
        // notes) has no end-of-queue/track-completed callback — only
        // currentMediaItemIndex/isPlaying state. REPEAT_TRACK and looping
        // REPEAT_QUEUE back to index 0 both need that signal to act on; find it
        // (or poll positionMs vs durationMs near track end) before this does
        // anything beyond persisting the toggle state.
    }

    fun release() = player.release()

    private fun Track.toAudioItem(api: SubsonicApi): LightAudioItem {
        val source = if (localFilePath != null) {
            LightAudioSource.FileSource(File(localFilePath))
        } else {
            LightAudioSource.UrlSource(api.streamUrl(id))
        }
        return LightAudioItem(
            source = source,
            metadata = LightMediaMetadata(
                title = title,
                artist = artistName,
                album = albumName,
                durationMs = durationSec * 1000L,
            ),
        )
    }
}

/**
 * Holds the single shared [PlaybackRepository] for the tool's lifetime.
 * `DefaultLightAudio` needs a `SealedLightActivity`, which only a Screen has —
 * whichever screen first triggers playback supplies it; later screens just call
 * [get] again and receive the same instance (they should already have an
 * activity reference too by construction, but it's ignored once created).
 */
object PlaybackRepositoryHolder {
    @Volatile private var instance: PlaybackRepository? = null

    fun get(
        sealedActivity: com.thelightphone.sdk.SealedLightActivity,
        apiHolder: SubsonicApiHolder,
    ): PlaybackRepository =
        instance ?: synchronized(this) {
            instance ?: PlaybackRepository(
                audio = com.thelightphone.sdk.audio.DefaultLightAudio(sealedActivity),
                apiHolder = apiHolder,
            ).also { instance = it }
        }

    /**
     * Non-creating lookup — for screens (Home) that want to show "now playing" state
     * if it exists, without themselves spending the app's one detached-audio handle
     * by creating a player before anything has actually been asked to play.
     */
    fun peek(): PlaybackRepository? = instance
}
