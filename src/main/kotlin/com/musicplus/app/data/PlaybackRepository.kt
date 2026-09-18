package com.musicplus.app.data

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
    private fun resolveIndex(queueSize: Int, realIndex: Int, pendingIndex: Int, playerQueueLoaded: Boolean): ResolvedIndex {
        return if (playerQueueLoaded && realIndex in 0 until queueSize) {
            ResolvedIndex(realIndex, isPending = false)
        } else {
            ResolvedIndex(pendingIndex.coerceIn(0, (queueSize - 1).coerceAtLeast(0)), isPending = true)
        }
    }

    private val resolvedIndex = combine(queue, player.currentMediaItemIndex, pendingIndex, playerQueueLoaded) { q, realIndex, pending, loaded ->
        resolveIndex(queueSize = q.size, realIndex = realIndex, pendingIndex = pending, playerQueueLoaded = loaded)
    }

    private val playerCore = combine(
        resolvedIndex, player.isPlaying, player.positionMs, player.durationMs, isActivelyLoading,
    ) { resolved, isPlaying, positionMs, durationMs, activelyLoading ->
        if (resolved.isPending) {
            PlayerCoreState(resolved.index, isPlaying = false, positionMs = 0L, durationMs = 0L, isLoading = activelyLoading)
        } else {
            PlayerCoreState(resolved.index, isPlaying, positionMs, durationMs, isLoading = false)
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
            isLoading = core.isLoading,
        )
    }

    /** The explicit album-art hint passed to the current [play] call, if any — see [currentAlbumArtUrl]'s doc. */
    val albumArtUrlHint: StateFlow<String?> = currentAlbumArtUrl.asStateFlow()

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
    private val nearEndCompletionWatcher = scope.launch {
        state.collect { s ->
            val mode = repeatMode.value
            if (mode == RepeatMode.OFF || !s.isPlaying || s.durationMs <= 0L) return@collect
            val remainingMs = s.durationMs - s.positionMs
            val nearEnd = remainingMs in 0..NEAR_END_THRESHOLD_MS
            if (!nearEnd) {
                // Cleared the instant we're not near-end — for a freshly
                // restarted/wrapped track this won't be true again until it's
                // played nearly all the way through once more, so this is
                // never a same-position re-arm race.
                lastHandledCompletionIndex = -1
                return@collect
            }
            // Edge-detector: without this, every emission still inside the
            // near-end window would refire the action (repeated seekTo(0)
            // calls, or repeatedly restarting the queue wrap).
            if (lastHandledCompletionIndex == s.currentIndex) return@collect
            when (mode) {
                RepeatMode.REPEAT_TRACK -> {
                    lastHandledCompletionIndex = s.currentIndex
                    player.seekTo(0)
                    if (!player.isPlaying.value) player.play()
                }
                RepeatMode.REPEAT_QUEUE -> {
                    // Mid-queue, nothing to do — ExoPlayer already auto-advances
                    // to the next item on its own; this only needs to step in at
                    // the wrap-around point that behavior doesn't cover.
                    if (s.currentIndex == s.queue.lastIndex) {
                        lastHandledCompletionIndex = s.currentIndex
                        play(s.queue, 0)
                    }
                }
                RepeatMode.OFF -> {}
            }
        }
    }
    private var lastHandledCompletionIndex = -1

    // Restore must finish (including its Room/DataStore reads) before the
    // queue-persistence collector below starts — both run in one coroutine,
    // sequentially, specifically so the collector's first emission is never
    // the class's own empty initial `queue` value racing ahead of
    // restoreFromDisk() and overwriting the very row it's about to read. See
    // restoreFromDisk's doc for the restore itself.
    init {
        scope.launch {
            restoreFromDisk()
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
        val index = player.currentMediaItemIndex.value.coerceIn(0, tracks.lastIndex)
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
            isPlaying = if (isPending) false else player.isPlaying.value,
            positionMs = if (isPending) 0L else player.positionMs.value,
            durationMs = if (isPending) 0L else player.durationMs.value,
            shuffle = shuffle.value,
            repeatMode = repeatMode.value,
            errorMessage = player.error.value?.let { "${it.kind}: ${it.diagnostic}" },
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
            val startItem = tracks[startIndex].toAudioItem(api)
            AppLogger.d("PlaybackRepository", "play(): starting track resolved, calling setMediaQueue")
            player.setMediaQueue(listOf(startItem), 0)
            playerQueueLoaded.value = true
            player.play()
        } finally {
            isActivelyLoading.value = false
        }
        // Persist the new position right away rather than waiting out
        // statePersistenceWatcher's interval — cheap, and means a kill right
        // after starting a new track still resumes from roughly the right
        // place instead of wherever the *previous* track was.
        persistScalarStateIfLoaded()

        if (tracks.size > 1) {
            scope.launch { extendToFullQueue(tracks, startIndex, api, myGeneration) }
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
        val items = tracks.map { it.toAudioItem(api) }
        if (playGeneration != generation) return // superseded while resolving — nothing to extend anymore
        val savedPositionMs = player.positionMs.value
        val wasPlaying = player.isPlaying.value
        queue.value = tracks
        pendingIndex.value = startIndex
        player.setMediaQueue(items, startIndex)
        withTimeoutOrNull(REBUILD_DURATION_WAIT_MS) {
            player.durationMs.first { it > 0L }
        }
        player.seekTo(savedPositionMs)
        if (wasPlaying) player.play()
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
        playGeneration++
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
        if (queue.value.isEmpty()) {
            play(tracks, 0)
            return
        }
        rebuildQueue(queue.value + tracks)
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
        val currentIndex = player.currentMediaItemIndex.value.coerceIn(0, current.lastIndex)
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
        val currentIndex = explicitIndex ?: player.currentMediaItemIndex.value.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
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
            player.pause()
            scope.launch { persistScalarStateIfLoaded() }
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
        if (repeatMode.value == RepeatMode.REPEAT_QUEUE && player.currentMediaItemIndex.value == current.lastIndex) {
            scope.launch { play(current, 0) }
        } else {
            player.skipToNext()
        }
    }

    /** Same fix as [skipToNext], the other direction — tapping "previous" on the first track under REPEAT_QUEUE wraps to the last one instead of doing nothing. */
    fun skipToPrevious() {
        val current = queue.value
        if (repeatMode.value == RepeatMode.REPEAT_QUEUE && player.currentMediaItemIndex.value == 0) {
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
        val currentIndex = player.currentMediaItemIndex.value.coerceIn(0, (current.size - 1).coerceAtLeast(0))
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
        restoredPositionMs = 0L
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
        val source = when {
            localFilePath != null -> LightAudioSource.FileSource(File(localFilePath))
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
        val cached = File(cacheDir, "$id-${maxBitRateKbps ?: "orig"}.mp3")
        if (!cached.exists()) {
            AppLogger.d("PlaybackRepository", "cachedStreamFile($id): not cached, downloading")
            try {
                // Streams straight to disk — see SubsonicClient.downloadToFile's
                // doc: the old `cached.writeBytes(api.streamBytes(id))` briefly
                // held the whole track as one in-memory ByteArray, which crashed
                // the app outright (OutOfMemoryError) on a real ~30MB track.
                api.streamToFile(id, cached, maxBitRateKbps)
                AppLogger.d("PlaybackRepository", "cachedStreamFile($id): write complete")
            } catch (e: Exception) {
                // A failed/interrupted download can leave a truncated file at
                // `cached`'s path — the exists() check above would otherwise
                // treat that as a legitimate cache hit forever after, playing
                // back a corrupt partial file instead of ever retrying.
                cached.delete()
                throw e
            }
        } else {
            AppLogger.d("PlaybackRepository", "cachedStreamFile($id): already cached")
        }
        return cached
    }

    private companion object {
        /** See [rebuildQueue]'s doc — caps how long a queue edit waits for the rebuilt player to resolve a real duration before seeking. */
        const val REBUILD_DURATION_WAIT_MS = 5_000L

        /** See [nearEndCompletionWatcher]'s doc — how close to the end counts as "finished" for the REPEAT_TRACK/REPEAT_QUEUE workaround. */
        const val NEAR_END_THRESHOLD_MS = 500L

        /** See [statePersistenceWatcher]'s doc — how often the resume position is checkpointed to disk during ordinary playback. */
        const val STATE_PERSIST_INTERVAL_MS = 5_000L
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
