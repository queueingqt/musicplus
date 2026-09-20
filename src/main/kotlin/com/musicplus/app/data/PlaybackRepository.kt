package com.musicplus.app.data

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Sleep timer state — ephemeral, in-memory only, by design (see
 * [PlaybackRepository]'s sleep-timer section): resets to `null` on every
 * app restart, nothing here is persisted.
 *
 * [Countdown.totalMs] is the duration originally selected, carried
 * alongside [Countdown.remainingMs] (which ticks down every second)
 * specifically so SleepTimerPickerScreen can tell which preset/custom row
 * is currently active — comparing against [remainingMs] directly would
 * only ever match for the first second, since it decays continuously after
 * that.
 */
sealed class SleepTimerState {
    data class Countdown(val remainingMs: Long, val totalMs: Long) : SleepTimerState()
    data object EndOfTrack : SleepTimerState()
}

/**
 * Fires [action] at most once per distinct [index] — the shared shape
 * behind [PlaybackRepository]'s four edge-detectors
 * (lastHandledCompletionIndex/lastHandledSleepTimerIndex in
 * [PlaybackRepository.nearEndCompletionWatcher], lastNowPlayingIndex/
 * lastScrobbledIndex in [PlaybackRepository.scrobbleWatcher]): each
 * previously hand-rolled an identical `if (lastHandledX != index) {
 * lastHandledX = index; action() }` pattern, already called "edge-detector"
 * in this file's own comments even before being pulled out — confirmed
 * live, 2026-09-18 architecture review.
 */
private class EdgeDetector {
    private var lastFiredIndex = -1
    fun reset() {
        lastFiredIndex = -1
    }
    suspend fun fireOnce(index: Int, action: suspend () -> Unit) {
        if (lastFiredIndex == index) return
        lastFiredIndex = index
        action()
    }
}

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
    private val libraryRepository: LibraryRepository,
    private val queueDao: QueueDao,
    private val playbackStateRepository: PlaybackStateRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val connectivity: LightConnectivity,
) {
    private val player = audio.newPlayer(playback = LightAudioPlayback.Detached)

    // Process-lifetime, same reasoning as AppGraph's own appScope — this
    // repository is itself a process-lifetime singleton (see
    // PlaybackRepositoryHolder), so nothing here needs a narrower scope tied
    // to any particular screen's composition.
    //
    // Main, not Default: every path through this scope eventually calls
    // `player.*` (pause/play/setMediaQueue/seekTo), which wraps a media3
    // MediaController — those methods throw IllegalStateException off the
    // thread that created the controller (main). Confirmed on-device,
    // 2026-09-17: playAsync()'s launch crashed on exactly this
    // ("MediaController method is called from a wrong thread") the first
    // time anything actually ran on this scope with Dispatchers.Default.
    // Suspend calls that do real network I/O (toAudioItem/cachedStreamFile)
    // stay non-blocking regardless — Ktor's CIO engine dispatches its own
    // socket I/O internally rather than blocking the caller's dispatcher.
    //
    // The exception handler is the last line of defence: a failure that reaches it
    // (a track that couldn't be fetched, say) is logged and shown as an error line
    // instead of taking the whole app down. Before it existed, an uncaught
    // download timeout in a coroutine on this scope crashed the app 8 times in
    // one evening (2026-09-19). The known failure points below also catch their
    // own errors so they can leave playback in a sensible state.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e ->
                AppLogger.e("PlaybackRepository", "uncaught in a playback coroutine", e)
                loadError.value = "Couldn't load audio: ${loadFailureReason(e)}"
            },
    )

    /**
     * Why the last attempt to get a track (or the rest of the queue) ready failed,
     * shown on Now Playing like any player error. Separate from `player.error`:
     * when the file can't even be fetched, the player never gets anything to fail
     * on, and the screen used to look like an ordinary load that never finished.
     * Cleared when a new play starts.
     */
    private val loadError = MutableStateFlow<String?>(null)

    // toString(), not the exception's class: the Light SDK's build check forbids reflection.
    private fun loadFailureReason(e: Throwable): String = when {
        e.toString().contains("Timeout", ignoreCase = true) -> "the server took too long"
        e is java.io.IOException -> e.message ?: "network error"
        else -> e.message ?: e.toString()
    }

    private val queue = MutableStateFlow<List<Track>>(emptyList())
    private val shuffle = MutableStateFlow(false)
    private val repeatMode = MutableStateFlow(RepeatMode.OFF)

    // The whole queue's id order captured the moment shuffle turns on
    // (including already-played tracks), so turning it back off can restore
    // it exactly — see setShuffle's doc. Null means "not currently shuffled"
    // (nothing to restore).
    private val preShuffleOrder = MutableStateFlow<List<String>?>(null)

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

    // True once `player.setMediaQueue(...)` has actually been called for the
    // *current* `queue`/`pendingIndex` pair — reset to false at the start of
    // every `beginPlay()`, set true at the end of `play()`. Restoring a
    // persisted queue on process start (see [restoreFromDisk]) populates
    // `queue`/`pendingIndex` directly without ever touching the real player,
    // so `player.currentMediaItemIndex.value` (very plausibly still its
    // default, 0) can coincidentally fall inside the restored queue's bounds
    // by pure luck — `resolvedIndex` below used to trust that as "the real
    // player caught up," which would show whatever the player's stale
    // default state happened to be instead of the actually-restored track.
    // This flag disambiguates "index 0 happens to be in range" from "the
    // player has genuinely loaded this queue."
    private val playerQueueLoaded = MutableStateFlow(false)

    // False only while the player holds just the *starting* track of a longer
    // queue: play() loads that one track first so audio starts at once, and
    // extendToFullQueue swaps the whole queue in later — 30-100 s later when the
    // rest has to be downloaded. In that window the player's own index is
    // relative to its one-item list (always 0), not to `queue`, so anything that
    // read it as a queue index showed queue position 0 as "now playing" — the
    // wrong title, the marker on row 0, the wrong track scrobbled — while the
    // audio was the track that was tapped (issue #47, seen on the phone
    // 2026-09-19). See [playerQueueIndex].
    private val playerHoldsFullQueue = MutableStateFlow(true)

    // True only for the real network-bound window inside `play()` — distinct
    // from `resolvedIndex.isPending` below, which is also true for the
    // (non-loading) "restored from disk, waiting for the user to press play"
    // state. Conflating the two would show a permanent loading spinner after
    // every restore instead of a plain paused/ready state.
    private val isActivelyLoading = MutableStateFlow(false)

    // Set once by restoreFromDisk() from the last persisted position, consumed
    // (and cleared) the first time togglePlayPause() actually resumes a
    // restored queue — see its doc. Zero once consumed or if nothing was
    // ever restored, same as a track legitimately starting from 0:00.
    private var restoredPositionMs = 0L

    // Set synchronously at the very top of beginPlay(), i.e. before any real
    // play() has done a single suspend — see restoreFromDisk()'s doc for why
    // this exists. Never cleared: once a real play has ever been started this
    // process, restoring old state to overwrite it is never correct again.
    private var hasStartedRealPlay = false

    // Bumped synchronously in beginPlay(), alongside hasStartedRealPlay —
    // lets play()'s background queue-extension (see extendToFullQueue's doc)
    // recognize it's been superseded by a newer play()/beginPlay() call and
    // bail instead of clobbering it. Deliberately not a `queue.value`
    // identity check: updateTrackFavorite() legitimately replaces `queue`
    // with a new List (same tracks, one's isFavorite patched) for reasons
    // that have nothing to do with superseding an in-flight play.
    private var playGeneration = 0

    // The background full-queue rebuild started by play() — kept so the next
    // beginPlay() can cancel it outright instead of leaving a superseded run
    // downloading a whole queue behind the new one (issue #47).
    private var extendJob: Job? = null

    // One lock per stream-cache file name, so two callers that want the same
    // track (a re-tapped queue row while the previous rebuild is still fetching
    // it) share one download instead of both writing the same file (issue #47).
    private val streamFileLocks = ConcurrentHashMap<String, Mutex>()

    // Completed once restoreFromDisk() has finished or been skipped — see
    // awaitQueueRestored().
    private val restoreDone = CompletableDeferred<Unit>()

    // Nested combines rather than one wide call — kotlinx.coroutines only has typed
    // `combine` overloads up to 5 flows; this keeps every step on solid ground
    // instead of reaching for the untyped Array<T> vararg overload.
    private data class PlayerCoreState(val index: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long, val isLoading: Boolean)

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

    /**
     * The shared "is the real player's current index trustworthy for the
     * current queue, or do we still need `pendingIndex`" branch — needed
     * identically by the live `resolvedIndex` flow below and by
     * [currentSnapshot], its synchronous twin used to seed a fresh screen's
     * `StateFlow` before any emission can land. These two used to each
     * reimplement this by hand: a fix for the restored-but-never-loaded-queue
     * case (see `playerQueueLoaded`'s doc) landed in the live `resolvedIndex`
     * flow first, and [currentSnapshot] kept flashing stale Now Playing art/
     * title on navigation until the same gap was found and patched there
     * separately — confirmed on-device 2026-09-18. Pulled out here so the two
     * call sites structurally cannot drift apart like that again.
     *
     * Not loaded yet, or `realIndex` outside `[0, queueSize)` (the real
     * player hasn't caught up to a just-started queue/index yet, or never
     * loaded one at all — see `playerQueueLoaded`'s doc), falls back to
     * `pendingIndex` coerced into the queue's valid range; otherwise the real
     * player's index is trustworthy as-is.
     */
    private fun resolveIndex(
        queueSize: Int,
        realIndex: Int,
        pendingIndex: Int,
        playerQueueLoaded: Boolean,
        playerHoldsFullQueue: Boolean,
    ): ResolvedIndex {
        val coercedPending = pendingIndex.coerceIn(0, (queueSize - 1).coerceAtLeast(0))
        return when {
            // The player holds only the starting track, so its own index is 0 no
            // matter where that track sits in the queue — see playerHoldsFullQueue.
            // Playback state (position, playing) is real here; only the index isn't.
            playerQueueLoaded && !playerHoldsFullQueue -> ResolvedIndex(coercedPending, isPending = false)
            playerQueueLoaded && realIndex in 0 until queueSize -> ResolvedIndex(realIndex, isPending = false)
            else -> ResolvedIndex(coercedPending, isPending = true)
        }
    }

    private val resolvedIndex = combine(queue, player.currentMediaItemIndex, pendingIndex, playerQueueLoaded, playerHoldsFullQueue) { q, realIndex, pending, loaded, holdsFull ->
        resolveIndex(queueSize = q.size, realIndex = realIndex, pendingIndex = pending, playerQueueLoaded = loaded, playerHoldsFullQueue = holdsFull)
    }

    /**
     * The queue index the player is really on. Everything that edits or reads the
     * queue relative to "what is playing" (remove, move, shuffle, the repeat wrap,
     * persistence) must use this rather than `player.currentMediaItemIndex`, which
     * is relative to whatever list the player holds — see [playerHoldsFullQueue].
     */
    private fun playerQueueIndex(): Int =
        if (playerHoldsFullQueue.value) player.currentMediaItemIndex.value else pendingIndex.value

    /**
     * What the play/pause icon shows while the player is being re-prepared under it —
     * the full queue handed over after a song starts ([extendToFullQueue]) or after a queue
     * edit ([rebuildQueue]). `setMediaQueue` makes the real `isPlaying` drop out and come
     * back, twice, over about 0.8 s; the icon followed it, PAUSE -> PLAY -> PAUSE -> PLAY ->
     * PAUSE, on every song start (reported on the phone, 2026-09-19). null = show the real
     * state. Set only for the duration of one hand-over, by [holdingPlayIcon].
     */
    private val playingOverride = MutableStateFlow<Boolean?>(null)

    private val shownPlaying = combine(player.isPlaying, playingOverride) { real, override -> override ?: real }

    private val playerCore = combine(
        resolvedIndex, shownPlaying, player.positionMs, player.durationMs, isActivelyLoading,
    ) { resolved, isPlaying, positionMs, durationMs, activelyLoading ->
        if (resolved.isPending) {
            PlayerCoreState(resolved.index, isPlaying = false, positionMs = 0L, durationMs = 0L, isLoading = activelyLoading)
        } else {
            // Loaded, but play() is still waiting for the player to report that audio has
            // started — see the wait in play(). Without this the icon showed PLAY for the
            // ~0.5 s between the queue being handed over and playback beginning, so a song
            // start flickered loading -> play -> pause.
            PlayerCoreState(resolved.index, isPlaying, positionMs, durationMs, isLoading = activelyLoading && !isPlaying)
        }
    }

    private data class MiscState(val shuffle: Boolean, val repeatMode: RepeatMode, val errorMessage: String?)

    // error was previously dropped entirely — a real playback failure (bad stream
    // URL, auth, unsupported format) looked identical in the UI to "still loading",
    // which is exactly what happened testing against a real server: track metadata
    // showed correctly (queue is set before the player even touches the network)
    // but position/duration silently stayed 0:00 with no visible cause.
    private val misc = combine(shuffle, repeatMode, player.error, loadError) { isShuffle, mode, error, loadErr ->
        MiscState(isShuffle, mode, error?.let { "${it.kind}: ${it.diagnostic}" } ?: loadErr)
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
            isLoading = core.isLoading,
        )
    }

    /** The explicit album-art hint passed to the current [play] call, if any — see [currentAlbumArtUrl]'s doc. */
    val albumArtUrlHint: StateFlow<String?> = currentAlbumArtUrl.asStateFlow()

    // Declared before the watchers below (rather than trailing them, as the
    // plain -1 Int fields they replaced could get away with) — a StateFlow
    // collector can start delivering emissions synchronously as part of the
    // scope.launch call that creates it, so a real object referenced by a
    // watcher's lambda has to already exist by then, not just be scheduled
    // to exist later in constructor order.
    private val completionEdge = EdgeDetector()
    private val nowPlayingEdge = EdgeDetector()
    private val scrobbledEdge = EdgeDetector()

    // Position-polling workaround for REPEAT_TRACK/REPEAT_QUEUE, since
    // LightAudioPlayer's confirmed public surface has no track-completion or
    // end-of-queue signal to react to instead — filed upstream as
    // lightphone/light-sdk#218 ("LightAudioPlayer doesn't expose events").
    // If/when that lands, replace this whole watcher with reacting to the
    // real event directly, instead of estimating "about to end" from
    // positionMs/durationMs. Position updates every 250ms (confirmed in the
    // SDK's own LightAudioPlayer source, POSITION_POLL_MS), so a 500ms
    // "near end" window gives a couple of ticks of margin to act before the
    // track would actually finish and (for a multi-track queue) ExoPlayer's
    // own default auto-advance-to-next-item takes over on its own — the
    // trade-off is losing the last ~0.5s of a REPEAT_TRACK loop, which reads
    // as far less jarring than the alternative (letting it actually advance
    // to the next track first, then snapping back).
    //
    // REPEAT_TRACK/REPEAT_QUEUE only — the sleep timer's own "end of current
    // track" mode used to be handled inside this same collect block (it needs
    // the identical near-end technique, for the identical reason: no real
    // completion event to react to), which meant one watcher owned
    // edge-detection for two entirely unrelated features. Split into its own
    // sleepTimerWatcher (see the Sleep timer section below) — confirmed live,
    // 2026-09-18 architecture review.
    private val nearEndCompletionWatcher = scope.launch {
        state.collect { s ->
            if (!s.isPlaying || s.durationMs <= 0L) return@collect
            val remainingMs = s.durationMs - s.positionMs
            val nearEnd = remainingMs in 0..NEAR_END_THRESHOLD_MS
            if (!nearEnd) {
                // Cleared the instant we're not near-end — for a freshly
                // restarted/wrapped track this won't be true again until it's
                // played nearly all the way through once more, so this is
                // never a same-position re-arm race.
                completionEdge.reset()
                return@collect
            }

            val mode = repeatMode.value
            if (mode == RepeatMode.OFF) return@collect
            // Edge-detector: without this, every emission still inside the
            // near-end window would refire the action (repeated seekTo(0)
            // calls, or repeatedly restarting the queue wrap).
            when (mode) {
                RepeatMode.REPEAT_TRACK -> completionEdge.fireOnce(s.currentIndex) {
                    player.seekTo(0)
                    if (!player.isPlaying.value) player.play()
                }
                RepeatMode.REPEAT_QUEUE -> {
                    // Mid-queue, nothing to do — ExoPlayer already auto-advances
                    // to the next item on its own; this only needs to step in at
                    // the wrap-around point that behavior doesn't cover.
                    if (s.currentIndex == s.queue.lastIndex) {
                        completionEdge.fireOnce(s.currentIndex) { play(s.queue, 0) }
                    }
                }
                RepeatMode.OFF -> {}
            }
        }
    }

    // Scrobbling — off by default (see AppSettingsRepository.scrobblingEnabled's
    // doc). "Now playing" notification (submission=false) fires once a track
    // starts; the real scrobble (submission=true) fires once it's played past
    // the standard Last.fm threshold: half its duration or 4 minutes, whichever
    // is smaller, and never at all for a track under 30s — the same rule
    // Last.fm's own clients use, not something Subsonic's scrobble.view itself
    // enforces (it'll happily record a scrobble at any position if asked).
    // nowPlayingEdge/scrobbledEdge, same EdgeDetector shape as
    // nearEndCompletionWatcher's own — a manual seek backward past an
    // already-scrobbled position correctly does NOT re-fire, since both
    // compare against currentIndex, not position.
    private val scrobbleWatcher = scope.launch {
        combine(state, appSettingsRepository.scrobblingEnabled) { s, enabled -> s to enabled }
            .collect { (s, enabled) ->
                if (!enabled || !s.isPlaying || s.durationMs <= 0L) return@collect
                val track = s.currentTrack ?: return@collect

                nowPlayingEdge.fireOnce(s.currentIndex) { sendScrobble(track.id, submission = false) }

                if (s.durationMs < MIN_SCROBBLE_DURATION_MS) return@collect
                val thresholdMs = minOf(s.durationMs / 2, SCROBBLE_THRESHOLD_MS)
                if (s.positionMs >= thresholdMs) {
                    scrobbledEdge.fireOnce(s.currentIndex) { sendScrobble(track.id, submission = true) }
                }
            }
    }

    // Playback errors (issues #47/#50). The SDK's LightAudioError only ever
    // reached the screen as Now Playing's "Playback error: ..." line — nothing
    // recorded it, so an ERROR_CODE_IO_UNSPECIFIED that hit right as a track
    // started left no trace anywhere and its cause could not be worked out
    // afterward. Logs every distinct error with the track and queue position it
    // hit and, because a Source (I/O) failure at the very start of a track has
    // been seen to clear on a fresh attempt, retries the current track once —
    // at most once per AUTO_RETRY_COOLDOWN_MS per track, and only within the
    // first AUTO_RETRY_MAX_POSITION_MS of it, so a failure mid-song never yanks
    // the listener back to 0:00.
    private val lastAutoRetryAtMs = HashMap<String, Long>()
    private val playerErrorWatcher = scope.launch {
        player.error.collect { err ->
            if (err == null) return@collect
            val s = currentSnapshot()
            val track = s.currentTrack
            AppLogger.e(
                "PlaybackRepository",
                "player error ${err.kind}: ${err.diagnostic} (item ${err.itemIndex}); track=${track?.id} " +
                    "\"${track?.title}\" queueIndex=${s.currentIndex} of ${s.queue.size} position=${s.positionMs}ms",
            )
            if (err.kind != LightAudioErrorKind.Source || track == null) return@collect
            // A file that is gone (its download was removed while the song sat in the
            // queue) is not a flaky start, and `s.positionMs` says nothing about it: the
            // player can still be reporting the previous item's position, which is what
            // stopped the retry on the phone (2026-09-19) and left playback stalled in
            // an error until play was pressed. Re-resolving the track fetches it instead.
            val fileGone = err.diagnostic.contains("FILE_NOT_FOUND")
            if (!fileGone && s.positionMs > AUTO_RETRY_MAX_POSITION_MS) return@collect
            val now = System.currentTimeMillis()
            val last = lastAutoRetryAtMs[track.id]
            if (last != null && now - last < AUTO_RETRY_COOLDOWN_MS) return@collect
            lastAutoRetryAtMs[track.id] = now
            AppLogger.d("PlaybackRepository", "auto-retrying ${track.id} once after ${err.diagnostic}")
            delay(AUTO_RETRY_DELAY_MS)
            if (s.currentIndex in s.queue.indices) playAsync(s.queue, s.currentIndex, currentAlbumArtUrl.value)
        }
    }

    /**
     * Fire-and-forget on its own child coroutine — scrobbling must never block
     * or otherwise affect actual playback. Failures are logged AND surfaced to
     * [AppScrobblePrefs.lastError] (cleared on the next success): required,
     * not swallowed — see that property's own doc for why.
     */
    private fun sendScrobble(songId: String, submission: Boolean) {
        scope.launch {
            val api = apiHolder.get() ?: return@launch
            try {
                api.scrobble(songId, submission)
                AppLogger.d("PlaybackRepository", "scrobble(songId=$songId, submission=$submission) succeeded")
                AppScrobblePrefs.lastError.set(null)
            } catch (e: Exception) {
                AppLogger.e("PlaybackRepository", "scrobble(songId=$songId, submission=$submission) failed", e)
                AppScrobblePrefs.lastError.set(e.message ?: "Scrobble failed")
            }
        }
    }

    // --- Sleep timer — ephemeral, in-memory only, by design (see
    // [SleepTimerState]'s doc): resets to null on every app restart, nothing
    // here is persisted. Lives here, on this repository's own [scope],
    // rather than any one screen's viewModelScope for the same reason
    // [scope] itself does (see this class's header doc): PlaybackRepository
    // is a process-lifetime singleton tied to the same
    // LightAudioPlayback.Detached foreground service that already keeps
    // playback alive across backgrounding, so a timer counting down here
    // keeps counting down exactly as reliably as playback itself does — no
    // WorkManager/AlarmManager needed.
    //
    // One feature, two genuinely different mechanisms, kept together here
    // rather than scattered across the class the way they previously were.
    // [SleepTimerState.Countdown] has a fixed duration to count down, so
    // [startSleepTimer] runs its own tick loop on [sleepTimerJob].
    // [SleepTimerState.EndOfTrack] has no fixed duration — there's nothing
    // to tick — so [startSleepTimerAtEndOfTrack] instead arms
    // [sleepTimerWatcher] below, which polls playback position the same way
    // [nearEndCompletionWatcher] does for REPEAT_TRACK/REPEAT_QUEUE (no
    // track-completion event exists to react to instead — see that
    // watcher's own doc). That's a real, deliberate difference in how the
    // two modes work, not an oversight: collapsing it further into one
    // wall-clock-driven mechanism would mean a countdown started while
    // paused either doesn't count down (wrong for "sleep in 30 minutes,
    // playing or not") or an end-of-track timer fires based on elapsed real
    // time instead of actual playback progress (wrong the other way —
    // pausing to answer the door shouldn't burn down the timer). What WAS
    // wrong: EndOfTrack's polling used to live inside
    // nearEndCompletionWatcher's own collect block, which meant one watcher
    // owned edge-detection for two entirely unrelated features (repeat-mode
    // wrap-around and the sleep timer). Split apart here instead — confirmed
    // live, 2026-09-18 architecture review.
    private val _sleepTimerState = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimerState: StateFlow<SleepTimerState?> = _sleepTimerState.asStateFlow()

    // The running countdown coroutine, if any — null in EndOfTrack mode (see
    // class doc above; that mode is driven by [sleepTimerWatcher] instead,
    // which has no job of its own since it just observes [state]).
    private var sleepTimerJob: Job? = null

    private val sleepTimerEdge = EdgeDetector()

    // Only acts while [_sleepTimerState] is [SleepTimerState.EndOfTrack] — a
    // no-op collector otherwise. See the Sleep timer section doc above for
    // why this needs its own near-end poll rather than a fixed duration to
    // count down, and why it's a separate watcher from
    // [nearEndCompletionWatcher] rather than sharing its collect block.
    private val sleepTimerWatcher = scope.launch {
        state.collect { s ->
            if (_sleepTimerState.value !is SleepTimerState.EndOfTrack) return@collect
            if (!s.isPlaying || s.durationMs <= 0L) return@collect
            val remainingMs = s.durationMs - s.positionMs
            val nearEnd = remainingMs in 0..NEAR_END_THRESHOLD_MS
            if (!nearEnd) {
                sleepTimerEdge.reset()
                return@collect
            }
            sleepTimerEdge.fireOnce(s.currentIndex) {
                pauseForSleepTimer()
                _sleepTimerState.value = null
            }
        }
    }

    /**
     * Starts (or restarts, replacing whatever was already running/set) a
     * plain countdown sleep timer — pauses playback once [minutes] has
     * elapsed. See [SleepTimerState.Countdown.totalMs]'s doc for why the
     * originally-selected duration is kept alongside the live, ticking-down
     * remaining time.
     */
    fun startSleepTimer(minutes: Int) {
        require(minutes > 0) { "minutes must be positive" }
        val totalMs = minutes * 60_000L
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState.Countdown(remainingMs = totalMs, totalMs = totalMs)
        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                val tick = SLEEP_TIMER_TICK_MS.coerceAtMost(remaining)
                delay(tick)
                remaining -= tick
                _sleepTimerState.value = SleepTimerState.Countdown(remainingMs = remaining, totalMs = totalMs)
            }
            pauseForSleepTimer()
            _sleepTimerState.value = null
            sleepTimerJob = null
        }
    }

    /** "End of current track" — see the Sleep timer section doc above for why this arms [sleepTimerWatcher] instead of running a tick loop. */
    fun startSleepTimerAtEndOfTrack() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.EndOfTrack
    }

    /** Cancels a running sleep timer outright (either kind) — playback itself is untouched either way. */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = null
    }

    /**
     * The sleep timer's only effect — pause, and only if actually playing
     * right now; nothing else (no stop, no clearQueue: [queue]/[pendingIndex]
     * stay exactly as they were). Guarded on [player.isPlaying] rather than
     * calling [togglePlayPause] unconditionally — a timer that fires after
     * the person already paused by hand must stay paused, not toggle
     * straight back into play.
     */
    private fun pauseForSleepTimer() {
        if (player.isPlaying.value) {
            player.pause()
            scope.launch { persistScalarStateIfLoaded() }
        }
    }

    // Restore must finish (including its Room/DataStore reads) before the
    // queue-persistence collector below starts — both run in one coroutine,
    // sequentially, specifically so the collector's first emission is never
    // the class's own empty initial `queue` value racing ahead of
    // restoreFromDisk() and overwriting the very row it's about to read. See
    // restoreFromDisk's doc for the restore itself.
    init {
        scope.launch {
            try {
                restoreFromDisk()
            } finally {
                restoreDone.complete(Unit)
            }
            queue.collect { tracks -> queueDao.replaceQueue(tracks.map { it.id }) }
        }
    }

    // Coarse periodic snapshot, not a full `state` collector — position
    // updates every ~250ms (see nearEndCompletionWatcher's doc) and a
    // DataStore write is real disk I/O; issue #27 only needs "close enough"
    // position to resume from, not per-tick accuracy. Also triggered
    // immediately at a few specific moments (end of play(), pause, shuffle/
    // repeat changes) so those don't wait out the interval.
    private val statePersistenceWatcher = scope.launch {
        while (true) {
            delay(STATE_PERSIST_INTERVAL_MS)
            persistScalarStateIfLoaded()
        }
    }

    /**
     * Issue #27 — restores the persisted queue (song ids, in order, from
     * [queueDao]) and the scalar state that goes with it ([playbackStateRepository])
     * on process start, so the queue survives an app restart instead of
     * starting empty every time.
     *
     * Deliberately does NOT touch [player] at all — no `setMediaQueue`, no
     * network fetch — just populates [queue]/[pendingIndex]/[shuffle]/
     * [repeatMode]/[currentAlbumArtUrl] directly, the same synchronous fields
     * [beginPlay] sets, so every screen's title/art/mode indicators show the
     * restored state immediately without forcing a cold-start network fetch
     * nobody asked for yet. The real player only loads it once the user
     * actually presses play — see [togglePlayPause]'s resume branch, which is
     * what [restoredPositionMs] is for.
     *
     * Any persisted song id no longer in the local Room cache (e.g. app data
     * was cleared, or it was never fetched into the local library at all) is
     * silently dropped rather than failing the whole restore — see
     * [LibraryRepository.getTracksByIds]'s doc.
     *
     * Checks [hasStartedRealPlay] both before starting and right before
     * applying its result — reproduced live, 2026-09-18: force-close the app,
     * relaunch, and tap a track to play as close to immediately as possible.
     * This function's own DB reads below are genuine suspend I/O (the very
     * first Room query of the process, including a cold SQLite open plus the
     * hand-rolled migration checks in `MusicPlusDatabase.create()`), which on
     * a fresh process can easily take longer than resolving an already-cached
     * track's stream URL in [play]. Both this function and a fresh play() are
     * launched on the same [scope] with no ordering guarantee between them —
     * whichever finishes last wins the write to [queue]/[pendingIndex], and
     * without this check that was reliably the stale restore, silently
     * overwriting the track the person had just tapped with whatever was
     * playing last session.
     */
    private suspend fun restoreFromDisk() {
        if (hasStartedRealPlay) return // a real play already started before this function even began — nothing to restore over
        val songIds = queueDao.observeQueue().first().map { it.songId }
        if (songIds.isEmpty()) return
        val tracksById = libraryRepository.getTracksByIds(songIds).associateBy { it.id }
        val tracks = songIds.mapNotNull { tracksById[it] }
        if (tracks.isEmpty()) return // none of the persisted tracks are in the local cache anymore
        val saved = playbackStateRepository.read()
        if (hasStartedRealPlay) return // a real play started while these reads were in flight — don't clobber it
        queue.value = tracks
        pendingIndex.value = saved.currentIndex.coerceIn(0, tracks.lastIndex)
        // Guards against resurrecting an invalid combo saved before shuffle/
        // REPEAT_TRACK became mutually exclusive (see setShuffle/setRepeatMode) —
        // shuffle wins, same as if the two were toggled in that order live.
        shuffle.value = saved.shuffle
        repeatMode.value = if (saved.shuffle && saved.repeatMode == RepeatMode.REPEAT_TRACK) RepeatMode.OFF else saved.repeatMode
        currentAlbumArtUrl.value = saved.albumArtUrl
        restoredPositionMs = saved.positionMs
        AppLogger.d(
            "PlaybackRepository",
            "restoreFromDisk(): restored ${tracks.size}/${songIds.size} track(s), index=${pendingIndex.value}, positionMs=${saved.positionMs}",
        )
    }

    /** No-ops until [playerQueueLoaded] — nothing new to persist while still restoring/mid-load, and [player]'s own index/position aren't trustworthy yet either (see [resolvedIndex]). */
    private suspend fun persistScalarStateIfLoaded() {
        if (!playerQueueLoaded.value) return
        val tracks = queue.value
        if (tracks.isEmpty()) return
        val index = playerQueueIndex().coerceIn(0, tracks.lastIndex)
        playbackStateRepository.save(
            PlaybackStateRepository.Saved(
                currentIndex = index,
                positionMs = player.positionMs.value,
                shuffle = shuffle.value,
                repeatMode = repeatMode.value,
                albumArtUrl = currentAlbumArtUrl.value,
            ),
        )
    }

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
        // Same branch `resolvedIndex` resolves live, via the shared
        // [resolveIndex] — see its doc for why this can't go back to being a
        // hand-rolled copy: it already drifted from the live flow once, and
        // this snapshot kept flashing stale Now Playing art/title until that
        // was caught, confirmed on-device 2026-09-18.
        val resolved = resolveIndex(
            queueSize = q.size,
            realIndex = player.currentMediaItemIndex.value,
            pendingIndex = pendingIndex.value,
            playerQueueLoaded = playerQueueLoaded.value,
            playerHoldsFullQueue = playerHoldsFullQueue.value,
        )
        val isPending = resolved.isPending
        val index = resolved.index
        // Same reasoning as playerCore's pending branch: position/isPlaying still
        // describe the *previous* track during this window, so borrowing them
        // here would pair the new track's title/art with the old track's real,
        // actively-moving playhead — confirmed on-device 2026-09-18 as a genuine
        // "wrong audio shown as playing" bug, not just a cosmetic mismatch.
        return PlaybackState(
            queue = q,
            currentIndex = index,
            isPlaying = if (isPending) false else (playingOverride.value ?: player.isPlaying.value),
            positionMs = if (isPending) 0L else player.positionMs.value,
            durationMs = if (isPending) 0L else player.durationMs.value,
            shuffle = shuffle.value,
            repeatMode = repeatMode.value,
            errorMessage = player.error.value?.let { "${it.kind}: ${it.diagnostic}" } ?: loadError.value,
            isLoading = isPending,
        )
    }

    /**
     * [albumArtUrl]: pass it when the caller already has it (e.g. playing a
     * track from within an album screen) — see [currentAlbumArtUrl]'s doc for
     * why.
     *
     * Issue #7: `setMediaQueue` takes every item's source resolved up front —
     * there's no lazy/per-item resolution in the confirmed `LightAudioPlayer`
     * API — so resolving the *whole* queue before this call, as an earlier
     * version of `play()` did, meant a multi-track "play album" tap on a
     * cleartext (http://) server (see `toAudioItem`: a full download per
     * track) blocked audibly starting on downloading the entire album first,
     * not just the track that was about to play. Loads just the starting
     * track first instead — one file, so near-instant regardless of server
     * scheme — and hands the rest of the queue to [extendToFullQueue] to fill
     * in around it once the audio is already flowing.
     */
    suspend fun play(tracks: List<Track>, startIndex: Int, albumArtUrl: String? = null) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return // not configured — nothing playable
        beginPlay(tracks, startIndex, albumArtUrl)
        val myGeneration = playGeneration
        isActivelyLoading.value = true
        try {
            AppLogger.d("PlaybackRepository", "play(): resolving starting track (index=$startIndex of ${tracks.size})")
            val startItem = try {
                tracks[startIndex].toAudioItem(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The file couldn't be fetched (server slow or unreachable). Say so
                // rather than leaving a track that looks like it is loading forever;
                // pressing play tries again (playerQueueLoaded is still false).
                AppLogger.e("PlaybackRepository", "play(): could not load \"${tracks[startIndex].title}\"", e)
                loadError.value = "Couldn't load \"${tracks[startIndex].title}\": ${loadFailureReason(e)}"
                return
            }
            AppLogger.d("PlaybackRepository", "play(): starting track resolved, calling setMediaQueue")
            player.setMediaQueue(listOf(startItem), 0)
            // Only the starting track is in the player for now, unless it is the
            // whole queue — extendToFullQueue flips this once the full queue lands.
            // Set before playerQueueLoaded so there is no moment where the player's
            // one-item index 0 is read as a queue index.
            playerHoldsFullQueue.value = tracks.size <= 1
            playerQueueLoaded.value = true
            player.play()
            // Keep the loading state until audio actually starts (or it fails, or 3 s pass):
            // the player takes about half a second between play() and reporting isPlaying,
            // and the icon used to show PLAY for that gap before flipping to PAUSE.
            withTimeoutOrNull(START_PLAYBACK_WAIT_MS) {
                combine(player.isPlaying, player.error) { playing, error -> playing || error != null }.first { it }
            }
        } finally {
            isActivelyLoading.value = false
        }
        // Persist the new position right away rather than waiting out
        // statePersistenceWatcher's interval — cheap, and means a kill right
        // after starting a new track still resumes from roughly the right
        // place instead of wherever the *previous* track was.
        persistScalarStateIfLoaded()

        if (tracks.size > 1) {
            extendJob = scope.launch {
                try {
                    extendToFullQueue(tracks, startIndex, api, myGeneration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The current track is already playing; only the rest of the queue
                    // could not be fetched. Keep playing it, and say what happened —
                    // "next" still works, it starts the following track from the queue.
                    AppLogger.e("PlaybackRepository", "could not load the rest of the queue", e)
                    if (playGeneration == myGeneration) loadError.value = "Couldn't load the rest of the queue: ${loadFailureReason(e)}"
                }
            }
        }
    }

    /**
     * Fills in the rest of [tracks] around the one track [play] already
     * started audio flowing from — see [play]'s doc for why that's
     * deliberately narrowed to one track first. Reuses [rebuildQueue]'s exact
     * position/play-state-preserving swap (capture position + playing state,
     * `setMediaQueue` the full list, seek back, resume if it was playing), so
     * this carries the same known tradeoff issue #23 already documents for
     * any `setMediaQueue` call mid-playback: a brief (~1/4-1/2s) audible
     * pause when the fuller queue swaps in. Traded here for not blocking on
     * the *whole* queue's download before any audio starts at all, which is
     * the actual reported problem.
     *
     * [generation] is the [playGeneration] captured at the start of the
     * [play] call this extends — checked before applying the resolved queue
     * so a newer play()/beginPlay() that started (and finished) entirely
     * while this was still resolving tracks doesn't get clobbered by a
     * now-stale extension landing after it.
     */
    private suspend fun extendToFullQueue(tracks: List<Track>, startIndex: Int, api: SubsonicApi, generation: Int) {
        // Resolved one track at a time, checking the generation before each:
        // for a track that isn't cached yet, toAudioItem is a full download, and
        // the old all-at-once `tracks.map { ... }` only checked after every track
        // was fetched — so a superseded run kept downloading an entire queue (35
        // tracks was ~100 s on the phone) after a later tap had already replaced
        // it, racing the newer run over the same cache files (issue #47).
        // beginPlay() also cancels extendJob outright; this check covers the gap
        // between two downloads.
        val items = ArrayList<LightAudioItem>(tracks.size)
        for (track in tracks) {
            if (playGeneration != generation) return
            items += track.toAudioItem(api)
        }
        if (playGeneration != generation) return // superseded while resolving — nothing to extend anymore
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        queue.value = tracks
        pendingIndex.value = startIndex
        holdingPlayIcon(wasPlaying) {
            player.setMediaQueue(items, startIndex)
            // The player now holds the whole queue. Flip only once its own index has
            // caught up to startIndex, so the switch from pendingIndex to the player's
            // index changes nothing on screen (issue #47).
            withTimeoutOrNull(FULL_QUEUE_SWAP_WAIT_MS) { player.currentMediaItemIndex.first { it == startIndex } }
            if (playGeneration == generation) playerHoldsFullQueue.value = true
            withTimeoutOrNull(REBUILD_DURATION_WAIT_MS) {
                player.durationMs.first { it > 0L }
            }
            player.seekTo(savedPositionMs)
            if (wasPlaying) player.play()
            settleAfterHandOver(wasPlaying)
        }
        persistScalarStateIfLoaded()
    }

    /**
     * The synchronous part of [play] — [queue]/[pendingIndex]/[currentAlbumArtUrl]
     * every screen's own `state.currentTrack` (via the pendingIndex fallback —
     * see its doc) resolves from, plus pausing whatever was already playing
     * (see [play]'s own doc for why that happens here and not after the slow
     * part). Split out so a caller can call this, then navigate to
     * PlayerScreen immediately, *then* kick off the slow suspend part —
     * instead of only navigating after [play] fully returns. Reported live:
     * tapping a track showed no feedback at all — not even a screen change —
     * until playback was already fully loaded and ready, since navigation
     * only happened after the whole (potentially multi-second, see
     * toAudioItem) call completed. Calling this first means PlayerScreen's
     * own `LaunchedEffect(track) { if (track == null) goBack() }` never sees
     * a null track to bounce off of, even for the very first play() of a
     * session — before this split, that gap (however brief) existed for real.
     */
    fun beginPlay(tracks: List<Track>, startIndex: Int, albumArtUrl: String? = null) {
        // Set first, synchronously, before anything else here — see
        // restoreFromDisk()'s doc. This has to win any race against it, not
        // just the `queue.value =` write two lines down.
        hasStartedRealPlay = true
        loadError.value = null
        playGeneration++
        // Whatever full-queue rebuild the previous play() left running is now
        // superseded — stop it instead of letting it keep downloading (issue #47).
        extendJob?.cancel()
        queue.value = tracks
        pendingIndex.value = startIndex
        currentAlbumArtUrl.value = albumArtUrl
        // A new queue/position is about to load — whatever the player was
        // previously loaded with (if anything) no longer matches `queue`, so
        // `resolvedIndex` must fall back to `pendingIndex` again until `play()`
        // finishes and sets this back to true.
        playerQueueLoaded.value = false
        // Pause whatever was already playing the instant the UI switches to the
        // new track's title/art (just above) — setMediaQueue below can take a
        // real, visible amount of time to resolve (see toAudioItem: a cleartext
        // server downloads the whole file first), and leaving the old item
        // running until then meant the previous song kept audibly playing under
        // the new song's displayed info. Reported live. The "pending" state
        // already shows 0:00/paused rather than the old track's real position —
        // this makes the actual audio match that, instead of just the numbers.
        player.pause()
    }

    /**
     * [beginPlay] + [play], but launches the slow (network-bound) part on this
     * repository's own long-lived [scope] instead of suspending — the caller-side
     * pattern of `beginPlay(...); navigateTo(::PlayerScreen); scope.launch { play(...) }`
     * looked right but wasn't: a screen's `rememberCoroutineScope()` is tied to its
     * own composition, and `navigateTo` disposes that composition, so the launched
     * coroutine got cancelled before `play()`'s suspend work ever ran — every tap
     * silently stuck at 0:00/0:00 with no log output, since the coroutine never
     * reached its first line. Three other screens (Songs/Favorites/Search) never
     * called `play()` at all, same bug via a different mistake. Confirmed root
     * cause of the tracked playback-stall bug, 2026-09-17. Screens should call this
     * instead of the beginPlay+launch+play pattern directly.
     */
    fun playAsync(tracks: List<Track>, startIndex: Int, albumArtUrl: String? = null) {
        beginPlay(tracks, startIndex, albumArtUrl)
        scope.launch { play(tracks, startIndex, albumArtUrl) }
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
        // A queue that is still being restored from disk looks empty. Without
        // waiting, the first "add to queue" after the app starts took the
        // empty-queue branch below, started playing, and threw away the saved
        // queue (seen on the phone, 2026-09-19: a saved 35-track queue became 1).
        awaitQueueRestored()
        if (queue.value.isEmpty()) {
            play(tracks, 0)
            return
        }
        rebuildQueue(queue.value + tracks)
    }

    /**
     * [addToQueue] on this repository's own [scope], for a caller that isn't a
     * suspend function (a confirmation dialog's callback) and must not depend on
     * a screen's scope, which is cancelled once that screen is covered. [onDone]
     * runs after the tracks are queued.
     */
    fun addToQueueAsync(tracks: List<Track>, onDone: () -> Unit = {}) {
        scope.launch {
            addToQueue(tracks)
            onDone()
        }
    }

    /**
     * Suspends until the persisted queue has been restored (or restore was
     * skipped because a real play already started), for at most
     * [RESTORE_WAIT_MS]. Anything that inspects [queue] on behalf of the user —
     * the "already in the queue" check, [addToQueue] — has to wait for this, or
     * right after launch it sees an empty queue.
     */
    suspend fun awaitQueueRestored() {
        withTimeoutOrNull(RESTORE_WAIT_MS) { restoreDone.await() }
    }

    /**
     * The "Clear queue" action — drops every other track (past and upcoming)
     * but leaves whatever's currently playing alone. Reported live: an
     * earlier version wiped the queue down to nothing and stopped playback
     * too, which isn't what "clear queue" means while something is actively
     * playing — it should behave like "clear everything except now," the
     * same restriction removeFromQueue/moveQueueItem already enforce, not a
     * full stop. Goes through [rebuildQueue] rather than
     * `setMediaQueue(emptyList())` so the current track's position/play
     * state carries over exactly like any other queue trim.
     */
    suspend fun clearQueue() {
        val current = queue.value
        if (current.isEmpty()) return
        val currentIndex = playerQueueIndex().coerceIn(0, current.lastIndex)
        rebuildQueue(listOf(current[currentIndex]))
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
        val currentIndex = playerQueueIndex()
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
        val currentIndex = playerQueueIndex()
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
     *
     * [explicitIndex]: for the common case (add/remove/move a track elsewhere
     * in the queue), the currently playing item's *position* in [newQueue] is
     * unchanged, so it's fine to re-derive it from the real player's own
     * current index. [applyShuffle] is the one caller where that's false —
     * shuffling/unshuffling moves the currently playing track to a different
     * index in [newQueue] on purpose, so it passes the real new index directly
     * instead of letting this re-derive the wrong (stale, pre-shuffle) one.
     */
    private suspend fun rebuildQueue(newQueue: List<Track>, explicitIndex: Int? = null) {
        if (!player.awaitReady()) return
        val api = apiHolder.get() ?: return
        val currentIndex = explicitIndex ?: playerQueueIndex().coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        val previousQueue = queue.value
        val previousPending = pendingIndex.value
        queue.value = newQueue
        pendingIndex.value = currentIndex
        val items = try {
            newQueue.map { it.toAudioItem(api) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A track that isn't downloaded couldn't be fetched: put the queue back
            // as it was rather than show one the player doesn't hold.
            AppLogger.e("PlaybackRepository", "rebuildQueue(): could not load a track, keeping the previous queue", e)
            queue.value = previousQueue
            pendingIndex.value = previousPending
            loadError.value = "Couldn't update the queue: ${loadFailureReason(e)}"
            return
        }
        holdingPlayIcon(wasPlaying) {
            player.setMediaQueue(items, currentIndex)
            withTimeoutOrNull(REBUILD_DURATION_WAIT_MS) {
                player.durationMs.first { it > 0L }
            }
            // A queue edit loads the whole queue into the player, even if it happened
            // while only the starting track was loaded — see playerHoldsFullQueue.
            playerHoldsFullQueue.value = true
            player.seekTo(savedPositionMs)
            if (wasPlaying) player.play()
            settleAfterHandOver(wasPlaying)
        }
    }

    /** Shows [playing] on the play/pause icon for the duration of [block] — see [playingOverride]. */
    private suspend fun holdingPlayIcon(playing: Boolean, block: suspend () -> Unit) {
        playingOverride.value = playing
        try {
            block()
        } finally {
            playingOverride.value = null
        }
    }

    /** Waits out the player's own re-preparing after a hand-over, so the real state is only shown again once it has stopped bouncing. */
    private suspend fun settleAfterHandOver(wasPlaying: Boolean) {
        if (wasPlaying) withTimeoutOrNull(HAND_OVER_RESUME_WAIT_MS) { player.isPlaying.first { it } }
        delay(HAND_OVER_SETTLE_MS)
    }

    /**
     * Ordinary pause/play toggle, except right after a restore (issue #27):
     * [restoreFromDisk] populates [queue]/[pendingIndex] without ever loading
     * the real player (see its doc), so the first press needs to actually
     * call [play] — with [restoredPositionMs] applied afterward — rather than
     * pause/play-ing a player that has nothing loaded. [playerQueueLoaded]
     * is what distinguishes the two cases, same signal [resolvedIndex] uses.
     */
    fun togglePlayPause() {
        val current = queue.value
        if (current.isNotEmpty() && !playerQueueLoaded.value) {
            val startIndex = pendingIndex.value.coerceIn(0, current.lastIndex)
            val resumePositionMs = restoredPositionMs
            restoredPositionMs = 0L
            scope.launch {
                play(current, startIndex, currentAlbumArtUrl.value)
                if (resumePositionMs > 0L) {
                    withTimeoutOrNull(REBUILD_DURATION_WAIT_MS) { player.durationMs.first { it > 0L } }
                    player.seekTo(resumePositionMs)
                }
            }
            return
        }
        if (player.isPlaying.value) {
            // The person's own pause wins over any hand-over still holding the icon.
            playingOverride.value = null
            player.pause()
            scope.launch { persistScalarStateIfLoaded() }
        } else if (player.error.value != null) {
            // After a playback error the player sits idle until it is prepared
            // again, so a bare play() does nothing — pressing play left the error
            // on screen for good (seen on the phone, 2026-09-19, issue #50).
            // Restart the current track instead, exactly as tapping it in the
            // queue does.
            val s = currentSnapshot()
            if (s.currentIndex in s.queue.indices) {
                playAsync(s.queue, s.currentIndex, currentAlbumArtUrl.value)
            } else {
                player.play()
            }
        } else {
            player.play()
        }
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()

    /**
     * REPEAT_QUEUE's wrap-to-start only happens automatically today via
     * [nearEndCompletionWatcher] detecting the last track nearing its own
     * natural end — `player.skipToNext()` is a plain call into the SDK's
     * player, which has no idea REPEAT_QUEUE exists at all, so tapping "next"
     * on the last track did nothing (the underlying player is already at the
     * end of its real queue). Reported live, 2026-09-18. Every other case
     * (not on the last track, or repeat isn't REPEAT_QUEUE) still just
     * defers to the real player's own skip.
     */
    fun skipToNext() {
        val current = queue.value
        if (repeatMode.value == RepeatMode.REPEAT_QUEUE && playerQueueIndex() == current.lastIndex) {
            scope.launch { play(current, 0) }
        } else {
            player.skipToNext()
        }
    }

    /** Same fix as [skipToNext], the other direction — tapping "previous" on the first track under REPEAT_QUEUE wraps to the last one instead of doing nothing. */
    fun skipToPrevious() {
        val current = queue.value
        if (repeatMode.value == RepeatMode.REPEAT_QUEUE && playerQueueIndex() == 0) {
            scope.launch { play(current, current.lastIndex) }
        } else {
            player.skipToPrevious()
        }
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    /**
     * Jumps directly to the track at [index] in the current queue — e.g.
     * tapping an arbitrary row in QueueScreen, not just skip±1 via the
     * transport controls (issue #28). LightAudioPlayer's confirmed public
     * surface has no incremental "jump to queue position N" primitive (see
     * [toAudioItem]'s doc / the tracked light-sdk#217 gap) — setMediaQueue
     * always re-resolves and replaces the whole queue, so this is really just
     * [play] again with the same track list and a new startIndex, starting
     * that track fresh from 0:00 rather than resuming any old position.
     */
    suspend fun jumpTo(index: Int) {
        val current = queue.value
        if (index !in current.indices) return
        play(current, index, currentAlbumArtUrl.value)
    }

    /** [jumpTo], launched on this repository's own [scope] — see [playAsync]'s doc for why a caller shouldn't use its own composable scope for this. */
    fun jumpToAsync(index: Int) {
        val current = queue.value
        if (index !in current.indices) return
        playAsync(current, index, currentAlbumArtUrl.value)
    }

    /**
     * Shuffles the *whole* queue — already-played tracks included, not just
     * upcoming ones — so an already-played track can come back around again
     * in the new order. Only the track actively playing right now stays put
     * (it can't retroactively un-play), moving to the front of the shuffled
     * order with everything else (before and after it alike) reshuffled
     * behind it. Explicit correction, 2026-09-18: an earlier version only
     * shuffled the upcoming portion and left already-played tracks locked in
     * place, which wasn't what was asked for.
     *
     * The pre-shuffle order of the *entire* queue is captured so disabling
     * shuffle restores it exactly — the currently playing track's index is
     * recalculated to wherever it actually falls in that restored order
     * (which may once again have tracks "before" it, now that the whole
     * queue round-trips through shuffle together) rather than staying
     * pinned to the front. A track added or removed while shuffled is
     * handled gracefully on restore (dropped if it's gone, appended at the
     * end if it's new and wasn't in the captured order).
     *
     * Mutually exclusive with REPEAT_TRACK (see [setRepeatMode]'s doc) —
     * turning shuffle on while repeating one track forever drops repeat back
     * to OFF, since "what's shuffled next" is meaningless when nothing ever
     * advances past the current track.
     */
    fun setShuffle(enabled: Boolean) {
        if (shuffle.value == enabled) return
        shuffle.value = enabled
        if (enabled && repeatMode.value == RepeatMode.REPEAT_TRACK) {
            repeatMode.value = RepeatMode.OFF
        }
        scope.launch {
            applyShuffle(enabled)
            persistScalarStateIfLoaded()
        }
    }

    private suspend fun applyShuffle(enabled: Boolean) {
        val current = queue.value
        val currentIndex = playerQueueIndex().coerceIn(0, (current.size - 1).coerceAtLeast(0))
        if (currentIndex !in current.indices) return
        val currentTrack = current[currentIndex]
        val rest = current.filterIndexed { i, _ -> i != currentIndex }
        if (rest.isEmpty()) return // nothing to shuffle or restore — only one track in the queue
        if (enabled) {
            preShuffleOrder.value = current.map { it.id }
            rebuildQueue(listOf(currentTrack) + rest.shuffled(), explicitIndex = 0)
        } else {
            val order = preShuffleOrder.value
            preShuffleOrder.value = null
            if (order == null) return // shuffle was never really applied (e.g. queue was empty when toggled) — nothing to restore
            val byId = current.associateBy { it.id }
            val restored = order.mapNotNull { byId[it] } + current.filter { it.id !in order }
            val newIndex = restored.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
            rebuildQueue(restored, explicitIndex = newIndex)
        }
    }

    /**
     * Real REPEAT_TRACK/REPEAT_QUEUE behavior — see [nearEndCompletionWatcher]
     * for how completion is detected (a documented workaround, not the real
     * fix — light-sdk#218 is the real fix). REPEAT_OFF needs no action here:
     * that's just the ordinary "let ExoPlayer do what it already does"
     * behavior (auto-advance mid-queue, stop at the end).
     *
     * Mutually exclusive with shuffle — switching *into* REPEAT_TRACK turns
     * shuffle off and restores the queue's pre-shuffle order, same reasoning
     * as [setShuffle]'s doc: repeating one track forever makes an upcoming
     * shuffled order meaningless, since nothing ever reaches it.
     */
    fun setRepeatMode(mode: RepeatMode) {
        repeatMode.value = mode
        if (mode == RepeatMode.REPEAT_TRACK && shuffle.value) {
            shuffle.value = false
            scope.launch {
                applyShuffle(false)
                persistScalarStateIfLoaded()
            }
        } else {
            scope.launch { persistScalarStateIfLoaded() }
        }
    }

    fun release() = player.release()

    /**
     * Resets every in-memory playback field to empty — used by
     * LocalDataRepository.clearAll() once it's wiped the persisted queue,
     * cached audio, and cached art out from under whatever this repository
     * still has loaded. Stops playback first: the currently playing track's
     * underlying file (a cleartext-server cache entry, or a download) may
     * have just been deleted, so continuing to play it would either error out
     * or keep going from an already-buffered chunk that can never be
     * seeked/replayed correctly again.
     */
    fun resetInMemoryState() {
        player.pause()
        queue.value = emptyList()
        pendingIndex.value = 0
        shuffle.value = false
        repeatMode.value = RepeatMode.OFF
        preShuffleOrder.value = null
        currentAlbumArtUrl.value = null
        playerQueueLoaded.value = false
        playerHoldsFullQueue.value = true
        restoredPositionMs = 0L
        cancelSleepTimer()
    }

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
     *
     * Both branches request [currentStreamMaxBitRateKbps] rather than always
     * the original file — see its doc for why (issue #7, a huge lossless
     * source file turning "just the starting track" back into a
     * multi-second block).
     */
    private suspend fun Track.toAudioItem(api: SubsonicApi): LightAudioItem {
        val maxBitRateKbps = currentStreamMaxBitRateKbps()
        // The queue keeps the Track it was built (or restored) with, so a download
        // removed afterwards leaves localFilePath pointing at a file that is gone —
        // jumping back to that song then failed with ERROR_CODE_IO_FILE_NOT_FOUND
        // (seen on the phone, 2026-09-19, after removing a download from Now
        // Playing). Trust the path only while the file is really there; otherwise
        // play it the way an undownloaded song is played.
        val downloadedFile = localFilePath?.let { File(it) }?.takeIf { it.isFile }
        val source = when {
            downloadedFile != null -> LightAudioSource.FileSource(downloadedFile)
            api.baseUrlIsHttps -> LightAudioSource.UrlSource(api.streamUrl(id, maxBitRateKbps))
            else -> LightAudioSource.FileSource(cachedStreamFile(api, maxBitRateKbps))
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

    /**
     * Picks Wi-Fi or cellular quality based on the connection actually in use
     * right now — read fresh on every call (not cached/observed), same
     * reasoning as reading [apiHolder] fresh each time: this only needs the
     * answer at the exact moment a track is about to be fetched, not a live
     * subscription. [LightConnectivity.currentStatus] requires
     * ACCESS_NETWORK_STATE, already granted (this SDK's own
     * `observeNetworkStatus()` — see AppGraph's reconnect observer — needs
     * the same permission and is already relied on elsewhere).
     */
    private suspend fun currentStreamMaxBitRateKbps(): Int? =
        if (connectivity.currentStatus.isWifi) {
            appSettingsRepository.streamQualityWifi.first()
        } else {
            appSettingsRepository.streamQualityCellular.first()
        }

    /**
     * [maxBitRateKbps] is folded into the cache filename (not just the
     * request) — otherwise a track cached once at one quality would keep
     * being reused forever afterward even after switching networks/quality,
     * since the plain `$id`-keyed cache has no way to tell "already have
     * this at the quality that's wanted right now" from "already have this
     * at some other quality." Means the same track can end up with more than
     * one cached copy at different qualities over time, an accepted tradeoff
     * (streamcache has no eviction policy regardless — see "Clear all local
     * data" for the manual escape hatch).
     */
    private suspend fun Track.cachedStreamFile(api: SubsonicApi, maxBitRateKbps: Int?): File {
        val cacheDir = File(filesDir, "streamcache").apply { mkdirs() }
        val name = "$id-${maxBitRateKbps ?: "orig"}.mp3"
        val cached = File(cacheDir, name)
        if (cached.exists()) {
            AppLogger.d("PlaybackRepository", "cachedStreamFile($id): already cached")
            return cached
        }
        // One download per file at a time, and written under a temporary name
        // then moved into place (issue #47): a file at `cached`'s path is now
        // always a finished one. Before, the download wrote straight to that
        // path, so a second caller arriving mid-download (the rebuild a queue
        // tap superseded, still running beside the new one — both visible in the
        // 2026-09-19 12:00 log) saw "already cached" on a half-written file, or
        // started a second download writing into the same file, and a cancelled
        // download deleted whatever was at that path.
        return streamFileLocks.getOrPut(name) { Mutex() }.withLock {
            if (cached.exists()) {
                AppLogger.d("PlaybackRepository", "cachedStreamFile($id): already cached")
                return@withLock cached
            }
            AppLogger.d("PlaybackRepository", "cachedStreamFile($id): not cached, downloading")
            val part = File(cacheDir, "$name.part")
            try {
                // Streams straight to disk — see SubsonicClient.downloadToFile's
                // doc: the old `cached.writeBytes(api.streamBytes(id))` briefly
                // held the whole track as one in-memory ByteArray, which crashed
                // the app outright (OutOfMemoryError) on a real ~30MB track.
                api.streamToFile(id, part, maxBitRateKbps)
                if (!part.renameTo(cached)) throw java.io.IOException("could not move $name.part into place")
                AppLogger.d("PlaybackRepository", "cachedStreamFile($id): write complete")
            } catch (e: Exception) {
                // A failed, interrupted or cancelled download now only ever
                // leaves its own .part file, never something at `cached`'s path
                // that the exists() check above would treat as a cache hit —
                // which is what used to play back a corrupt partial file instead
                // of ever retrying.
                part.delete()
                throw e
            }
            cached
        }
    }

    private companion object {
        /** See the wait at the end of [play] — the longest the loading icon stays up waiting for audio to start. */
        const val START_PLAYBACK_WAIT_MS = 3_000L

        /** See [settleAfterHandOver] — how long to wait for playback to resume after a queue hand-over. */
        const val HAND_OVER_RESUME_WAIT_MS = 1_500L

        /** See [settleAfterHandOver] — the player bounced for about 0.8 s after a hand-over on the phone; this covers it. */
        const val HAND_OVER_SETTLE_MS = 800L

        /** See [rebuildQueue]'s doc — caps how long a queue edit waits for the rebuilt player to resolve a real duration before seeking. */
        const val REBUILD_DURATION_WAIT_MS = 5_000L

        /** See [nearEndCompletionWatcher]'s doc — how close to the end counts as "finished" for the REPEAT_TRACK/REPEAT_QUEUE workaround. */
        const val NEAR_END_THRESHOLD_MS = 500L

        /** See [awaitQueueRestored] — the longest anything waits for the saved queue to be restored. */
        const val RESTORE_WAIT_MS = 3_000L

        /** See [extendToFullQueue] — the longest it waits for the player's own index to reach the start index after the full queue is swapped in. */
        const val FULL_QUEUE_SWAP_WAIT_MS = 2_000L

        /** See [playerErrorWatcher]'s doc — only an error this early in a track is retried automatically. */
        const val AUTO_RETRY_MAX_POSITION_MS = 3_000L

        /** See [playerErrorWatcher]'s doc — the same track is auto-retried at most once per this long. */
        const val AUTO_RETRY_COOLDOWN_MS = 30_000L

        /** See [playerErrorWatcher]'s doc — a beat before retrying, so the player has settled after the error. */
        const val AUTO_RETRY_DELAY_MS = 750L

        /** See [statePersistenceWatcher]'s doc — how often the resume position is checkpointed to disk during ordinary playback. */
        const val STATE_PERSIST_INTERVAL_MS = 5_000L

        /** See [startSleepTimer]'s doc — how often the live countdown's [SleepTimerState.Countdown.remainingMs] ticks. */
        const val SLEEP_TIMER_TICK_MS = 1_000L

        /** See [scrobbleWatcher]'s doc — Last.fm's own "don't scrobble anything shorter than this" rule. */
        const val MIN_SCROBBLE_DURATION_MS = 30_000L

        /** See [scrobbleWatcher]'s doc — Last.fm's own "half the track, or this, whichever is smaller" scrobble threshold. */
        const val SCROBBLE_THRESHOLD_MS = 240_000L
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

    /** [graph]: the whole composition root rather than threading each of the repositories/DAOs this needs individually through every call site — see AppGraph.Graph. */
    fun get(
        sealedActivity: com.thelightphone.sdk.SealedLightActivity,
        graph: AppGraph.Graph,
        filesDir: File,
    ): PlaybackRepository =
        instance ?: synchronized(this) {
            instance ?: PlaybackRepository(
                audio = com.thelightphone.sdk.audio.DefaultLightAudio(sealedActivity),
                apiHolder = graph.apiHolder,
                filesDir = filesDir,
                libraryRepository = graph.libraryRepository,
                queueDao = graph.database.queueDao(),
                playbackStateRepository = graph.playbackStateRepository,
                appSettingsRepository = graph.appSettingsRepository,
                connectivity = graph.connectivity,
            ).also { instance = it }
        }

    /**
     * Non-creating lookup — for screens (Home) that want to show "now playing" state
     * if it exists, without themselves spending the app's one detached-audio handle
     * by creating a player before anything has actually been asked to play.
     */
    fun peek(): PlaybackRepository? = instance
}

/**
 * `AppGraph.from(lightContext)` + [PlaybackRepositoryHolder.get] collapsed to
 * one call — the same 3-line sequence was hand-copied at every call site that
 * needs playback (album/artist/songs/playlist/favorites/search lists). Not an
 * extension on `LightScreen` itself: `SimpleLightScreen.lightContext` is
 * `protected`, invisible to a top-level extension function (confirmed by the
 * compiler, not assumed), so both it and [activity] — itself `internal` to
 * `:sdk:client`, which is exactly why every screen already retains its own
 * `SealedLightActivity` constructor property — stay explicit parameters here.
 */
fun playbackRepository(activity: SealedLightActivity, lightContext: SealedLightContext): PlaybackRepository =
    PlaybackRepositoryHolder.get(activity, AppGraph.from(lightContext), lightContext.filesDir)
