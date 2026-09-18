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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

    // Set synchronously by play()/rebuildQueue() at the same moment as `queue`,
    // before the slow `setMediaQueue()`/network step. `player.currentMediaItemIndex`
    // doesn't update until that slow step actually completes, so right after
    // starting a new track there's a real window where it's still 0/stale/out of
    // bounds for the *new* queue — during which `playerCore` below falls back to
    // this instead. Without it, Now Playing showed the wrong (or no) track's
    // title/art for that whole window on every single track change, confirmed
    // on-device 2026-09-18 — a different gap than the StateFlow cold-start one
    // fixed earlier, and not fixable by caching since the app itself doesn't
    // know the right answer yet at that moment.
    private val pendingIndex = MutableStateFlow(0)

    // Set synchronously by play(), same moment as queue/pendingIndex — an
    // explicit hint from the caller (when it already has one in hand, e.g. an
    // album screen playing one of its own tracks) for which art to show on Now
    // Playing, preferred over looking the track's own art up by album id.
    // Without this, even a track never before played individually still
    // seeded Now Playing's art from its own (uncached) coverArtUrl for one
    // frame before the async album lookup corrected it to the already-cached
    // album art — confirmed on-device 2026-09-18 as a real, if brief, flicker
    // on every new track within an already-browsed album, not just the
    // StateFlow cold-start case fixed earlier for a repeated track.
    private val currentAlbumArtUrl = MutableStateFlow<String?>(null)

    // Nested combines rather than one wide call — kotlinx.coroutines only has typed
    // `combine` overloads up to 5 flows; this keeps every step on solid ground
    // instead of reaching for the untyped Array<T> vararg overload.
    private data class PlayerCoreState(val index: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long)

    // Carries *whether* the index came from the real player or the pendingIndex
    // fallback, not just the resolved number — `playerCore` below needs to know,
    // because `player.isPlaying`/`positionMs`/`durationMs` still describe the
    // *previous* track for the whole pending window (the real player hasn't
    // switched over yet). An earlier version of this fix showed the new track's
    // title/art immediately but left position/isPlaying sourced from the old
    // track's still-live values — confirmed on-device 2026-09-18: title and
    // artwork updated instantly, but the audibly-playing audio and the moving
    // playhead both still belonged to the previous song. Zeroed out below
    // instead, rather than showing genuinely wrong data borrowed from a
    // different track.
    private data class ResolvedIndex(val index: Int, val isPending: Boolean)

    private val resolvedIndex = combine(queue, player.currentMediaItemIndex, pendingIndex) { q, realIndex, pending ->
        if (realIndex in q.indices) ResolvedIndex(realIndex, isPending = false)
        else ResolvedIndex(pending.coerceIn(0, (q.size - 1).coerceAtLeast(0)), isPending = true)
    }

    private val playerCore = combine(
        resolvedIndex, player.isPlaying, player.positionMs, player.durationMs,
    ) { resolved, isPlaying, positionMs, durationMs ->
        if (resolved.isPending) {
            PlayerCoreState(resolved.index, isPlaying = false, positionMs = 0L, durationMs = 0L)
        } else {
            PlayerCoreState(resolved.index, isPlaying, positionMs, durationMs)
        }
    }

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

    /** The explicit album-art hint passed to the current [play] call, if any — see [currentAlbumArtUrl]'s doc. */
    val albumArtUrlHint: StateFlow<String?> = currentAlbumArtUrl.asStateFlow()

    /**
     * Synchronous read of every constituent `.value` — every one of them is
     * genuinely `StateFlow`-backed (confirmed in `LightAudioPlayer`), so this is
     * a real snapshot, not a guess. Used to seed a fresh screen's `state.stateIn`
     * initial value instead of a blank `PlaybackState()`: without this, Now
     * Playing visibly flashed its art/title/track info to empty on every
     * navigation into the screen, even mid-playback with a live queue already
     * in hand — same root cause as AlbumDetailScreen's album flash, confirmed
     * on-device 2026-09-18.
     */
    fun currentSnapshot(): PlaybackState {
        val q = queue.value
        val realIndex = player.currentMediaItemIndex.value
        // Same fallback as `resolvedIndex` above, and for the same reason: right
        // after play() is called, this can be read before the player's own index
        // has caught up to the new queue — confirmed on-device 2026-09-18, this
        // exact gap was still flashing Now Playing's art/title even after the
        // live-flow fallback was added, because this snapshot bypassed it entirely.
        val isPending = realIndex !in q.indices
        val index = if (isPending) pendingIndex.value.coerceIn(0, (q.size - 1).coerceAtLeast(0)) else realIndex
        // Same reasoning as playerCore's pending branch: position/isPlaying still
        // describe the *previous* track during this window, so borrowing them
        // here would pair the new track's title/art with the old track's real,
        // actively-moving playhead — confirmed on-device 2026-09-18 as a genuine
        // "wrong audio shown as playing" bug, not just a cosmetic mismatch.
        return PlaybackState(
            queue = q,
            currentIndex = index,
            isPlaying = if (isPending) false else player.isPlaying.value,
            positionMs = if (isPending) 0L else player.positionMs.value,
            durationMs = if (isPending) 0L else player.durationMs.value,
            shuffle = shuffle.value,
            repeatMode = repeatMode.value,
            errorMessage = player.error.value?.let { "${it.kind}: ${it.diagnostic}" },
        )
    }

    /** [albumArtUrl]: pass it when the caller already has it (e.g. playing a track from within an album screen) — see [currentAlbumArtUrl]'s doc for why. */
    suspend fun play(tracks: List<Track>, startIndex: Int, albumArtUrl: String? = null) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return // not configured — nothing playable
        queue.value = tracks
        pendingIndex.value = startIndex
        currentAlbumArtUrl.value = albumArtUrl
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
     * Patches [trackId]'s favorite flag in the live queue in place — metadata
     * only, doesn't touch the player at all (no rebuildQueue/setMediaQueue
     * call), so it can't cause the reorder-style playback pause (issue #23).
     * Needed because [queue] is a snapshot captured at play()/rebuildQueue()
     * time: [LibraryRepository.setTrackFavorite] writes through to Room/the
     * server correctly on its own, but nothing else re-syncs an
     * already-queued [Track]'s now-stale `isFavorite` afterward — confirmed
     * live as the Now Playing star icon not updating after a successful
     * favorite toggle (issue #8).
     */
    fun updateTrackFavorite(trackId: String, isFavorite: Boolean) {
        queue.value = queue.value.map { if (it.id == trackId) it.copy(isFavorite = isFavorite) else it }
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
     *
     * The restart-to-0:00 bug (Forgejo #12): `LightAudioPlayer.seekTo(ms)`'s
     * confirmed implementation is `player.seekTo(ms.coerceIn(0L,
     * player.duration.validDuration()))`, and its own doc says outright "Unknown
     * duration clamps to zero" — `validDuration()` maps media3's `C.TIME_UNSET`
     * (duration not yet known) to `0`. `setMediaQueue`'s `setMediaItems(...,
     * C.TIME_UNSET) + prepare()` call synchronously resets `LightAudioPlayer`'s
     * own `durationMs` StateFlow to `0` as part of that same call (its
     * `updateDuration()` reads `player.duration`, which media3 documents as
     * `C.TIME_UNSET` until the freshly-set item has actually been probed —
     * `Player.getDuration()`: "or C.TIME_UNSET if the duration is not known").
     * So calling `seekTo(savedPositionMs)` immediately afterward — as this used
     * to — coerces the target down to `0` every time, regardless of
     * [savedPositionMs]: not a dispatch-ordering race (`PendingPlayerCommands`
     * runs queued commands synchronously and in order once ready), but this
     * exact clamp. Waiting here for `durationMs` to report the real,
     * newly-resolved duration before seeking lets the same clamp pass
     * [savedPositionMs] through unchanged. `withTimeoutOrNull` keeps a
     * pathological source (duration that never resolves) from hanging a queue
     * edit forever; on timeout this falls through to the old (broken-for-that-
     * case) behavior rather than getting stuck.
     */
    private suspend fun rebuildQueue(newQueue: List<Track>) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return
        val currentIndex = player.currentMediaItemIndex.value.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        queue.value = newQueue
        pendingIndex.value = currentIndex
        player.setMediaQueue(newQueue.map { it.toAudioItem(api) }, currentIndex)
        withTimeoutOrNull(REBUILD_DURATION_WAIT_MS) {
            player.durationMs.first { it > 0L }
        }
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
     * as before. See project memory / tracked Forgejo issues for the
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

    private companion object {
        /** See [rebuildQueue]'s doc — caps how long a queue edit waits for the rebuilt player to resolve a real duration before seeking. */
        const val REBUILD_DURATION_WAIT_MS = 5_000L
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
