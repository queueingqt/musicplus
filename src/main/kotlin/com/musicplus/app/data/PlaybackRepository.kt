package com.musicplus.app.data

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
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
    private val filesDir: File,
) {
    private val player = audio.newPlayer(playback = LightAudioPlayback.Detached)

    private val queue = MutableStateFlow<List<Track>>(emptyList())
    private val shuffle = MutableStateFlow(false)
    private val repeatMode = MutableStateFlow(RepeatMode.OFF)

    // Nested 4-way combines rather than one 8-way call — kotlinx.coroutines only has
    // typed `combine` overloads up to 5 flows; this keeps every step on solid ground
    // instead of reaching for the untyped Array<T> vararg overload.
    private data class PlayerCoreState(val index: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long)

    private val playerCore = combine(
        player.currentMediaItemIndex, player.isPlaying, player.positionMs, player.durationMs,
    ) { index, isPlaying, positionMs, durationMs -> PlayerCoreState(index, isPlaying, positionMs, durationMs) }

    private data class MiscState(val shuffle: Boolean, val repeatMode: RepeatMode, val errorMessage: String?)

    // error was previously dropped entirely — a real playback failure (bad stream
    // URL, auth, unsupported format) looked identical in the UI to "still loading",
    // which is exactly what happened testing against a real server: track metadata
    // showed correctly (queue is set before the player even touches the network)
    // but position/duration silently stayed 0:00 with no visible cause.
    private val misc = combine(shuffle, repeatMode, player.error) { isShuffle, mode, error ->
        MiscState(isShuffle, mode, error?.let { "${it.kind}: ${it.diagnostic}" })
    }

    val state = combine(queue, playerCore, misc) { q, core, misc ->
        PlaybackState(
            queue = q,
            currentIndex = core.index,
            isPlaying = core.isPlaying,
            positionMs = core.positionMs,
            durationMs = core.durationMs,
            shuffle = misc.shuffle,
            repeatMode = misc.repeatMode,
            errorMessage = misc.errorMessage,
        )
    }

    suspend fun play(tracks: List<Track>, startIndex: Int) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return // not configured — nothing playable
        queue.value = tracks
        // setMediaQueue takes every item's source resolved up front — there's no
        // lazy/per-item resolution in the confirmed LightAudioPlayer API — so for an
        // http:// server (see toAudioItem) this pre-fetches the WHOLE queue before
        // playback starts, not just the starting track. Correct but not great UX for
        // a multi-track queue on a cleartext server; tracked as a follow-up rather
        // than solved here.
        player.setMediaQueue(tracks.map { it.toAudioItem(api) }, startIndex)
        player.play()
    }

    /**
     * Appends [tracks] after the current queue instead of replacing it. If nothing
     * is queued yet, this is equivalent to [play] starting at index 0.
     *
     * `LightAudioPlayer`'s confirmed public surface only exposes
     * `setMediaQueue(items, startIndex)`, which always replaces the whole queue —
     * there's no incremental append. So this rebuilds the full queue from this
     * repository's own [queue] state (see [rebuildQueue]) and re-calls
     * `setMediaQueue`, capturing/restoring the current position and play state
     * around it so appending doesn't interrupt or rewind whatever is currently
     * playing.
     */
    suspend fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (queue.value.isEmpty()) {
            play(tracks, 0)
            return
        }
        rebuildQueue(queue.value + tracks)
    }

    /**
     * Removes the upcoming track at [index]. Only indices after the currently
     * playing one are eligible — the queue view only offers removal for upcoming
     * tracks, never the one actively playing (removing "the current track" would
     * mean deciding what plays next, which is really a skip, not a queue edit).
     */
    suspend fun removeFromQueue(index: Int) {
        val current = queue.value
        val currentIndex = player.currentMediaItemIndex.value
        if (index !in current.indices || index <= currentIndex) return
        rebuildQueue(current.toMutableList().also { it.removeAt(index) })
    }

    /**
     * Moves the upcoming track at [index] by [delta] slots (e.g. -1/+1 for the
     * up/down reorder buttons in the queue view). Both the source and destination
     * must be after the currently playing index — see [removeFromQueue].
     */
    suspend fun moveQueueItem(index: Int, delta: Int) {
        val current = queue.value
        val currentIndex = player.currentMediaItemIndex.value
        val targetIndex = index + delta
        if (index !in current.indices || targetIndex !in current.indices) return
        if (index <= currentIndex || targetIndex <= currentIndex) return
        rebuildQueue(current.toMutableList().also { it.add(targetIndex, it.removeAt(index)) })
    }

    /**
     * Re-calls `setMediaQueue` with [newQueue] in full (see [addToQueue]'s doc for
     * why), preserving the currently playing item's index/position/play state so
     * a queue edit elsewhere doesn't interrupt what's already playing.
     */
    private suspend fun rebuildQueue(newQueue: List<Track>) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return
        val currentIndex = player.currentMediaItemIndex.value.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        queue.value = newQueue
        player.setMediaQueue(newQueue.map { it.toAudioItem(api) }, currentIndex)
        player.seekTo(savedPositionMs)
        if (wasPlaying) player.play()
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

    /**
     * media3/ExoPlayer inside the SDK's own `LightAudioPlayer` enforces Android's
     * cleartext-traffic block independently of SubsonicClient's engine choice —
     * confirmed on-device via `LightAudioError.diagnostic` =
     * "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED" when handing it a `UrlSource` for an
     * http:// stream (2026-09-17). There's no exposed way to give LightAudioPlayer
     * a custom data source, so for a plain-http server this downloads the track
     * (via the same Ktor/CIO client SubsonicApi already uses) into a cache file and
     * plays that instead of streaming — for an https:// server it streams directly
     * as before. See project_lightwave memory / tracked Forgejo issues for the
     * follow-up (progressive streaming, not pre-download, for cleartext servers).
     */
    private suspend fun Track.toAudioItem(api: SubsonicApi): LightAudioItem {
        val source = when {
            localFilePath != null -> LightAudioSource.FileSource(File(localFilePath))
            api.baseUrlIsHttps -> LightAudioSource.UrlSource(api.streamUrl(id))
            else -> LightAudioSource.FileSource(cachedStreamFile(api))
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

    private suspend fun Track.cachedStreamFile(api: SubsonicApi): File {
        val cacheDir = File(filesDir, "streamcache").apply { mkdirs() }
        val cached = File(cacheDir, "$id.mp3")
        if (!cached.exists()) {
            cached.writeBytes(api.streamBytes(id))
        }
        return cached
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
        filesDir: File,
    ): PlaybackRepository =
        instance ?: synchronized(this) {
            instance ?: PlaybackRepository(
                audio = com.thelightphone.sdk.audio.DefaultLightAudio(sealedActivity),
                apiHolder = apiHolder,
                filesDir = filesDir,
            ).also { instance = it }
        }

    /**
     * Non-creating lookup — for screens (Home) that want to show "now playing" state
     * if it exists, without themselves spending the app's one detached-audio handle
     * by creating a player before anything has actually been asked to play.
     */
    fun peek(): PlaybackRepository? = instance
}
