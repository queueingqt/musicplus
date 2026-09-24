package com.musicplus.app.data

import com.musicplus.app.NoteModal
import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.musicplus.app.data.playback.ErrorRecovery
import com.musicplus.app.data.playback.JellyfinPlayReportSink
import com.musicplus.app.data.playback.ListenReporting
import com.musicplus.app.data.playback.PlaybackPersistence
import com.musicplus.app.data.playback.PlayerFault
import com.musicplus.app.data.playback.RETRY_DELAY_MS
import com.musicplus.app.data.playback.Recovery
import com.musicplus.app.data.playback.ScrobbleSink
import com.musicplus.app.data.playback.SkipMove
import com.musicplus.app.data.playback.SleepTimer
import com.musicplus.app.data.playback.TrackEnd
import com.musicplus.app.data.playback.TrackEndAction
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
import kotlinx.coroutines.coroutineScope
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
 * Wraps one [LightAudio]-provided player for the whole tool session. Built as
 * `LightAudioPlayback.Detached` — per the SDK reference notes, that's what
 * survives navigating away from PlayerScreen or the phone locking, and only one
 * detached handle may exist at a time, so this MUST be a single shared instance
 * (see [PlaybackRepositoryHolder]), never constructed fresh per screen.
 */
class PlaybackRepository(
    audio: LightAudio,
    private val apiHolder: ApiHolder,
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
    // persisted queue on process start (see [PlaybackPersistence.restore]) populates
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

    // Set once by PlaybackPersistence.restore() from the last persisted position, consumed
    // (and cleared) the first time togglePlayPause() actually resumes a
    // restored queue — see its doc. Zero once consumed or if nothing was
    // ever restored, same as a track legitimately starting from 0:00.
    private var restoredPositionMs = 0L

    // Set synchronously at the very top of beginPlay(), i.e. before any real
    // play() has done a single suspend — see PlaybackPersistence.restore()'s doc for why
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

    /**
     * The songs the player currently holds as a transcoded stream rather than a file — the ones it cannot seek in (see
     * [PlaybackState.canSeek]). Set by [noteStreams] wherever the player is given a queue.
     */
    private val streamingIds = MutableStateFlow<Set<String>>(emptySet())

    // Completed once PlaybackPersistence.restore() has finished or been skipped — see
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

    /**
     * The player's own duration, or, while it is still unknown, the one the library holds for the
     * track. A stream has no length until the server declares one or it has been read to the end,
     * and the player reports 0 until then, which showed as "0:51 / 0:00".
     */
    private fun shownDurationMs(playerMs: Long, track: Track?): Long =
        if (playerMs > 0L) playerMs else (track?.durationSec ?: 0) * 1000L

    val state = combine(queue, playerCore, misc, streamingIds) { q, core, misc, streaming ->
        PlaybackState(
            canSeek = q.getOrNull(core.index)?.id !in streaming,
            queue = q,
            currentIndex = core.index,
            isPlaying = core.isPlaying,
            positionMs = core.positionMs,
            durationMs = shownDurationMs(core.durationMs, q.getOrNull(core.index)),
            shuffle = misc.shuffle,
            repeatMode = misc.repeatMode,
            errorMessage = misc.errorMessage,
            isLoading = core.isLoading,
        )
    }

    /** The explicit album-art hint passed to the current [play] call, if any — see [currentAlbumArtUrl]'s doc. */
    val albumArtUrlHint: StateFlow<String?> = currentAlbumArtUrl.asStateFlow()

    // The sleep timer, and the "the track is about to end" decision that both it (its end-of-track mode) and repeat mode act on.
    val sleepTimer = SleepTimer(scope, onSleep = { pauseForSleepTimer() })
    private val trackEnd = TrackEnd()
    private val trackEndWatcher = scope.launch {
        state.collect { s ->
            when (trackEnd.observe(s, repeatMode.value, sleepTimer.endOfTrackArmed)) {
                TrackEndAction.RESTART_TRACK -> {
                    player.seekTo(0)
                    if (!player.isPlaying.value) player.play()
                }
                TrackEndAction.WRAP_QUEUE -> play(s.queue, 0)
                TrackEndAction.SLEEP -> sleepTimer.fire()
                null -> {}
            }
        }
    }

    /** The sleep timer's only effect: pause, and only if actually playing, so a timer firing after a manual pause stays paused. */
    private fun pauseForSleepTimer() {
        if (player.isPlaying.value) {
            player.pause()
            scope.launch { persistScalarStateIfLoaded() }
        }
    }

    // Reports listening (scrobbles, a Jellyfin server's play history). What counts as a listen is ListenTracker's business
    // and where it is reported is the sinks': see data/playback.
    private val listens = ListenReporting(
        scope = scope,
        sinks = listOf(
            ScrobbleSink(apiHolder, libraryRepository) { appSettingsRepository.scrobblingEnabled.first() },
            JellyfinPlayReportSink(apiHolder),
        ),
    ).also { it.start(state) }

    // Player errors (issues #47/#50): what to retry and what to skip is ErrorRecovery's policy; this carries it out.
    private val errorRecovery = ErrorRecovery()
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
            val fault = PlayerFault(isSourceError = err.kind == LightAudioErrorKind.Source, diagnostic = err.diagnostic)
            if (track != null && errorRecovery.indicatesUnreachableServer(fault)) {
                ServerScope.serverOf(track.id)?.let { apiHolder.reachability?.report(it, false) }
            }
            when (val recovery = errorRecovery.decide(fault, s, repeatMode.value, { TrackAvailability.isPlayable(it) }, System.currentTimeMillis())) {
                Recovery.None -> {}
                is Recovery.Skip -> skipUnavailable(recovery, s)
                Recovery.Retry -> {
                    AppLogger.d("PlaybackRepository", "auto-retrying ${track?.id} once after ${err.diagnostic}")
                    delay(RETRY_DELAY_MS)
                    if (s.currentIndex in s.queue.indices) playAsync(s.queue, s.currentIndex, currentAlbumArtUrl.value)
                }
            }
        }
    }

    private val skipStreakReset = scope.launch { player.isPlaying.collect { if (it) errorRecovery.onPlaying() } }

    /** The song that just failed cannot be played (its server is off or unreachable and the phone has no copy): say so briefly and carry out the move. */
    private fun skipUnavailable(skip: Recovery.Skip, s: PlaybackState) {
        when (val move = skip.move) {
            SkipMove.Stop -> {
                AppLogger.d("PlaybackRepository", "nothing left to play in the queue, stopping")
                player.pause()
                NoteModal.show("Server not reachable")
            }
            is SkipMove.To, is SkipMove.WrapTo -> {
                AppLogger.d("PlaybackRepository", "skipping \"${skip.track.title}\" (${skip.track.id}): its server is not reachable")
                NoteModal.show("Skipped \"${skip.track.title}\": server not reachable")
                if (move is SkipMove.To) jumpToAsync(move.index) else scope.launch { play(s.queue, (move as SkipMove.WrapTo).index) }
            }
        }
    }

    // Saving and restoring the queue and playback state across a restart is PlaybackPersistence's; this only applies what it restored.
    // Restore must finish before the queue writer starts (see its doc), and both run in this one coroutine, in that order.
    private val persistence = PlaybackPersistence(queueDao, playbackStateRepository) { libraryRepository.getTracksByIds(it) }

    init {
        scope.launch {
            try {
                persistence.restore(stillWanted = { !hasStartedRealPlay })?.let { restored ->
                    queue.value = restored.tracks
                    pendingIndex.value = restored.index
                    shuffle.value = restored.shuffle
                    repeatMode.value = restored.repeatMode
                    currentAlbumArtUrl.value = restored.albumArtUrl
                    restoredPositionMs = restored.positionMs
                }
            } finally {
                restoreDone.complete(Unit)
            }
            persistence.keepQueueSaved(queue)
        }
    }

    private val statePersistenceWatcher = persistence.savePeriodically(scope) { snapshotToSave() }

    /** What to save right now, or null while restoring or mid-load: nothing new to persist, and the player's own index and position are not trustworthy yet either (see [resolvedIndex]). */
    private fun snapshotToSave(): PlaybackStateRepository.Saved? {
        if (!playerQueueLoaded.value) return null
        val tracks = queue.value
        if (tracks.isEmpty()) return null
        return PlaybackStateRepository.Saved(
            currentIndex = playerQueueIndex().coerceIn(0, tracks.lastIndex),
            positionMs = player.positionMs.value,
            shuffle = shuffle.value,
            repeatMode = repeatMode.value,
            albumArtUrl = currentAlbumArtUrl.value,
        )
    }

    private suspend fun persistScalarStateIfLoaded() {
        snapshotToSave()?.let { persistence.save(it) }
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
            canSeek = q.getOrNull(index)?.id !in streamingIds.value,
            queue = q,
            currentIndex = index,
            isPlaying = if (isPending) false else (playingOverride.value ?: player.isPlaying.value),
            positionMs = if (isPending) 0L else player.positionMs.value,
            durationMs = shownDurationMs(if (isPending) 0L else player.durationMs.value, q.getOrNull(index)),
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
    suspend fun play(tracks: List<Track>, requestedIndex: Int, albumArtUrl: String? = null) {
        if (!player.awaitReady()) return
        // A song that cannot be played (its server is off or unreachable, nothing on the phone) is not started: the
        // first one after it that can be is.
        val startIndex = tracks.indices.firstOrNull { it >= requestedIndex && TrackAvailability.isPlayable(tracks[it]) } ?: requestedIndex
        if (startIndex != requestedIndex) NoteModal.show("Skipped \"${tracks[requestedIndex].title}\": server not reachable")
        beginPlay(tracks, startIndex, albumArtUrl)
        val myGeneration = playGeneration
        val startTrack = tracks[startIndex]
        // Set when the starting song streams from a plain-http server, whose file is then fetched in the background — see toAudioItem.
        var streamingApi: MusicApi? = null
        isActivelyLoading.value = true
        try {
            AppLogger.d("PlaybackRepository", "play(): resolving starting track (index=$startIndex of ${tracks.size})")
            val startItem = try {
                val startApi = apiFor(startTrack)
                startTrack.toAudioItem(startApi, streamIfUncached = true) { FetchGate.Priority.NOW_PLAYING }.also {
                    if (startApi != null && !startApi.baseUrlIsHttps && it.source is LightAudioSource.UrlSource) streamingApi = startApi
                }
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
            noteStreams(listOf(startTrack), listOf(startItem))
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

        // A lone song that is streaming goes through the same hand-over: it is swapped onto its file when that lands,
        // which is what makes it seekable (a stream started without a declared length is not) and keeps it in the cache.
        if (tracks.size > 1 || streamingApi != null) {
            extendJob = scope.launch {
                try {
                    // A song that is streaming has the connection to itself until audio is flowing: fetching the
                    // queue's files at the same time starved it for 95 s on a weak cellular link (2026-09-23).
                    if (streamingApi != null) awaitAudioStarted()
                    extendToFullQueue(tracks, startIndex, myGeneration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The current track is already playing; only the rest of the queue
                    // could not be fetched. Keep playing it, and say what happened —
                    // "next" still works, it starts the following track from the queue.
                    AppLogger.e("PlaybackRepository", "could not load the rest of the queue", e)
                    if (tracks.size > 1 && playGeneration == myGeneration) loadError.value = "Couldn't load the rest of the queue: ${loadFailureReason(e)}"
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
    /**
     * Turns every track into a player item, fetching whatever isn't on the phone yet
     * concurrently (see [FetchGate] for the order and the limit). Returns null if
     * [stillWanted] turned false, i.e. a newer play superseded this one. Any track
     * failing fails the whole call, as it did when this ran one track at a time.
     */
    /**
     * The api for the server that owns [track]. A queue can hold songs from more than one server (one saved
     * before a server switch keeps playing from the server it came from), so this is per track, not per queue.
     * Null when that server was removed: its downloaded songs still play, from their files, and only a song that
     * has to be streamed needs the api (see [toAudioItem]).
     */
    private suspend fun apiFor(track: Track): MusicApi? = apiHolder.forId(track.id)

    private suspend fun resolveAll(
        tracks: List<Track>,
        fetchRange: IntRange = tracks.indices,
        stillWanted: () -> Boolean = { true },
    ): List<LightAudioItem>? {
        val items = arrayOfNulls<LightAudioItem>(tracks.size)
        coroutineScope {
            for (index in tracks.indices) {
                launch {
                    if (!stillWanted()) return@launch
                    val track = tracks[index]
                    // Outside [fetchRange] a song that is not cached is handed over to stream rather than waited for.
                    items[index] = track.toAudioItem(apiFor(track), streamIfUncached = index !in fetchRange) {
                        priorityForSong(track.id) ?: FetchGate.Priority.QUEUE
                    }
                }
            }
        }
        if (!stillWanted()) return null
        return items.map { it!! }
    }

    /**
     * How urgently [songId] is wanted right now: the playing song, one of the next
     * few, elsewhere in the queue, or null when it isn't in the queue at all. Read
     * by [FetchGate] as it schedules, so a download of the song that is playing (or
     * about to) is served ahead of the rest of the backlog.
     */
    fun priorityForSong(songId: String): FetchGate.Priority? {
        val queued = queue.value
        val index = queued.indexOfFirst { it.id == songId }
        if (index < 0) return null
        val current = if (playerQueueLoaded.value) playerQueueIndex() else pendingIndex.value
        return when {
            index == current -> FetchGate.Priority.NOW_PLAYING
            index > current && index <= current + UP_NEXT_COUNT -> FetchGate.Priority.UP_NEXT
            else -> FetchGate.Priority.QUEUE
        }
    }

    /**
     * Records which of [tracks] are about to be handed to the player as [items] that it cannot seek in: a stream, when
     * it is a transcode (an original file is served whole, with a length). Call it just before `setMediaQueue`.
     */
    private suspend fun noteStreams(tracks: List<Track>, items: List<LightAudioItem>) {
        val transcoded = currentStreamMaxBitRateKbps() != null
        streamingIds.value =
            if (!transcoded) emptySet()
            else tracks.filterIndexed { i, _ -> items[i].source is LightAudioSource.UrlSource }.map { it.id }.toSet()
    }

    /** Suspends until the player reports audio playing or an error, or [STREAM_START_WAIT_MS] passes. */
    private suspend fun awaitAudioStarted() {
        withTimeoutOrNull(STREAM_START_WAIT_MS) {
            combine(player.isPlaying, player.error) { playing, error -> playing || error != null }.first { it }
        }
    }

    /**
     * Which songs are fetched into the stream cache before the queue is handed to the
     * player, given the one playing at [current]. On Wi-Fi every one, as it always was.
     * Off Wi-Fi only the playing song and the next [UP_NEXT_COUNT]: a whole queue is far
     * more than a cellular link can fetch in the time the playing song lasts (about 160 s
     * for seven songs at 0.22 MB/s on the phone, so the player had nothing after the first
     * one), and fetches running beside a streaming song starve it. The rest are handed over
     * to stream when they are reached, or as their file where one is already on the phone
     * (see [toAudioItem]); where the build cannot stream they are still fetched first.
     */
    private fun prefetchRange(current: Int, size: Int): IntRange =
        if (connectivity.currentStatus.isWifi) 0 until size else current..(current + UP_NEXT_COUNT)

    private suspend fun extendToFullQueue(tracks: List<Track>, startIndex: Int, generation: Int) {
        // Every track that isn't cached yet is a full download. They run together
        // but in the order FetchGate serves them — the next few songs first, then
        // the rest of the queue — and only as many at a time as the connection can
        // take. A run that a later tap has superseded (issue #47: a 35-track queue
        // was ~100 s of pointless downloading) stops starting new fetches, and
        // beginPlay() also cancels extendJob outright.
        val items = resolveAll(tracks, prefetchRange(startIndex, tracks.size)) { playGeneration == generation }
            ?: return // superseded while resolving — nothing to extend anymore
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        queue.value = tracks
        pendingIndex.value = startIndex
        holdingPlayIcon(wasPlaying) {
            noteStreams(tracks, items)
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
        // PlaybackPersistence.restore()'s doc. This has to win any race against it, not
        // just the `queue.value =` write two lines down.
        hasStartedRealPlay = true
        listens.beginPlay()
        loadError.value = null
        playGeneration++
        // Whatever full-queue rebuild the previous play() left running is now
        // superseded — stop it instead of letting it keep downloading (issue #47).
        extendJob?.cancel()
        // A new queue/position is about to load — whatever the player was
        // previously loaded with (if anything) no longer matches `queue`, so
        // `resolvedIndex` must fall back to `pendingIndex` again until `play()`
        // finishes and sets this back to true. First, before `queue` changes:
        // this scope is Main.immediate, so a collector runs at every assignment,
        // and with `queue` set first there was one emission pairing the new
        // song with the previous song's playing flag and position, which
        // scrobbled a song that had not started (2026-09-23).
        playerQueueLoaded.value = false
        queue.value = tracks
        pendingIndex.value = startIndex
        currentAlbumArtUrl.value = albumArtUrl
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
     * Drops [serverId]'s upcoming songs from the queue, for a server that was removed: they can no longer be fetched.
     * Only songs after the playing one go, and the playing song stays whichever server it is from (finishing it is
     * not a queue edit), same restriction as [removeFromQueue].
     */
    suspend fun removeServerFromQueue(serverId: String) {
        val current = queue.value
        if (current.isEmpty()) return
        val currentIndex = playerQueueIndex().coerceIn(0, current.lastIndex)
        val trimmed = current.filterIndexed { index, track -> index <= currentIndex || ServerScope.serverOf(track.id) != serverId }
        if (trimmed.size == current.size) return
        rebuildQueue(trimmed)
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
        val currentIndex = explicitIndex ?: playerQueueIndex().coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        val previousQueue = queue.value
        val previousPending = pendingIndex.value
        queue.value = newQueue
        pendingIndex.value = currentIndex
        val items = try {
            resolveAll(newQueue, prefetchRange(currentIndex, newQueue.size))!!
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
            noteStreams(newQueue, items)
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
     * [PlaybackPersistence.restore] populates [queue]/[pendingIndex] without ever loading
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

    // Ignored while the song is a stream the player cannot seek in (the screen dims the buttons; this covers a headset
    // button or anything else that gets here), rather than restarting it or doing nothing to no visible effect.
    fun skipBack() { if (currentSongCanSeek()) player.skipBack() }
    fun skipForward() { if (currentSongCanSeek()) player.skipForward() }

    private fun currentSongCanSeek(): Boolean = queue.value.getOrNull(playerQueueIndex())?.id !in streamingIds.value

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
        sleepTimer.cancel()
    }

    /**
     * media3/ExoPlayer inside the SDK's own `LightAudioPlayer` enforces Android's
     * cleartext-traffic block independently of SubsonicClient's engine choice —
     * confirmed on-device via `LightAudioError.diagnostic` =
     * "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED" when handing it a `UrlSource` for an
     * http:// stream (2026-09-17). There's no exposed way to give LightAudioPlayer
     * a custom data source, so for a plain-http server this downloads the track
     * (via the same Ktor/CIO client SubsonicApi already uses) into a cache file and
     * plays that instead of streaming. An https:// server streams directly.
     *
     * A build whose manifest overlay sets `usesCleartextTraffic` (see [playerCanFetch])
     * can stream a plain-http server too, but the cache stays what it was: a song with a
     * copy already on the phone plays from it, and songs are still fetched into the cache
     * ahead of time (see [prefetchRange]). Only [streamIfUncached] streams, and only a
     * song that is not cached yet: the one [play] is starting, so audio begins at once
     * instead of after the whole file (its file is fetched meanwhile, and the queue
     * hand-over swaps the playing song onto it), and songs past [prefetchRange] off Wi-Fi.
     *
     * Both branches request [currentStreamMaxBitRateKbps] rather than always
     * the original file — see its doc for why (issue #7, a huge lossless
     * source file turning "just the starting track" back into a
     * multi-second block).
     */
    private suspend fun Track.toAudioItem(
        api: MusicApi?,
        streamIfUncached: Boolean = false,
        rank: () -> FetchGate.Priority = { FetchGate.Priority.QUEUE },
    ): LightAudioItem {
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
            // Its server is off or cannot be reached and the phone has no copy: fetching would only stall the whole queue
            // build. It stays in the queue as an item that will not play, and playback skips it (see skipUnavailable).
            !TrackAvailability.serverUsable(ServerScope.serverOf(id)) ->
                TrackAvailability.streamCopy(id)?.let { LightAudioSource.FileSource(it) }
                    ?: LightAudioSource.FileSource(File(filesDir, "streamcache/${ServerScope.fileKey(id)}-unreachable"))
            api == null -> throw java.io.IOException("the server for \"$title\" is not set up")
            api.baseUrlIsHttps -> LightAudioSource.UrlSource(api.streamUrl(id, maxBitRateKbps))
            api.playerCanFetchDirectly && streamIfUncached && !streamCacheFile(maxBitRateKbps).exists() -> {
                AppLogger.d("PlaybackRepository", "toAudioItem($id): not cached, streaming it")
                LightAudioSource.UrlSource(api.streamUrl(id, maxBitRateKbps))
            }
            else -> LightAudioSource.FileSource(cachedStreamFile(api, maxBitRateKbps, rank))
        }
        AppLogger.d("PlaybackRepository", "toAudioItem($id): ${source::class.simpleName} ${(source as? LightAudioSource.FileSource)?.file?.name ?: ""} (downloaded=${downloadedFile != null}, serverUsable=${TrackAvailability.serverUsable(ServerScope.serverOf(id))})")
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
    private fun Track.streamCacheFile(maxBitRateKbps: Int?): File =
        File(File(filesDir, "streamcache"), "${ServerScope.fileKey(id)}-${maxBitRateKbps ?: "orig"}.mp3")

    private suspend fun Track.cachedStreamFile(api: MusicApi, maxBitRateKbps: Int?, rank: () -> FetchGate.Priority): File {
        val cacheDir = File(filesDir, "streamcache").apply { mkdirs() }
        val cached = streamCacheFile(maxBitRateKbps)
        val name = cached.name
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
                val fetched = FetchGate.run(FetchGate.Lane.PLAYBACK, id, rank, transcoding = maxBitRateKbps != null) { lease ->
                    api.streamToFile(id, part, maxBitRateKbps, lease)
                }
                if (fetched == null) throw java.io.IOException("the connection is busy with other transfers")
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
        /** How many songs after the playing one count as "up next" for [FetchGate]. */
        const val UP_NEXT_COUNT = 3

        /** See the wait at the end of [play] — the longest the loading icon stays up waiting for audio to start. */
        const val START_PLAYBACK_WAIT_MS = 3_000L

        /** See [awaitAudioStarted] — how long the queue's fetches are held back for a streaming song to start. */
        const val STREAM_START_WAIT_MS = 60_000L

        /** See [settleAfterHandOver] — how long to wait for playback to resume after a queue hand-over. */
        const val HAND_OVER_RESUME_WAIT_MS = 1_500L

        /** See [settleAfterHandOver] — the player bounced for about 0.8 s after a hand-over on the phone; this covers it. */
        const val HAND_OVER_SETTLE_MS = 800L

        /** See [rebuildQueue]'s doc — caps how long a queue edit waits for the rebuilt player to resolve a real duration before seeking. */
        const val REBUILD_DURATION_WAIT_MS = 5_000L


        /** See [awaitQueueRestored] — the longest anything waits for the saved queue to be restored. */
        const val RESTORE_WAIT_MS = 3_000L

        /** See [extendToFullQueue] — the longest it waits for the player's own index to reach the start index after the full queue is swapped in. */
        const val FULL_QUEUE_SWAP_WAIT_MS = 2_000L








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
