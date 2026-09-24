package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.FetchGate
import com.musicplus.app.data.PlaybackStateRepository
import com.musicplus.app.data.ServerScope
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why loading failed, for the line Now Playing shows. toString(), not the exception's class: the Light SDK's build check forbids reflection. */
internal fun loadFailureReason(e: Throwable): String = when {
    e.toString().contains("Timeout", ignoreCase = true) -> "the server took too long"
    e is java.io.IOException -> e.message ?: "network error"
    else -> e.message ?: e.toString()
}

/** What the player has been given for the current queue. It decides which index the screen shows and whether the queue can be edited without touching the player. */
enum class Hold {
    /** Nothing: a queue restored from disk and not started, one that was cleared, or a play that has not reached the player yet. The queue is all there is to edit. */
    NOTHING,

    /**
     * Part of it: the player holds only the song that is starting (a play loads that one first so audio begins at once; the rest is
     * handed over 30 to 100 s later when it has to be downloaded), or is being handed a new list. Its own index is relative to the
     * list it holds, not to the queue, so the queue's index is the one the app is heading for (issue #47: reading the player's index
     * here showed queue position 0 as playing, with the wrong title, the marker on row 0 and the wrong song scrobbled).
     */
    PARTIAL,

    /** The whole queue: the player's own index is the queue index. */
    WHOLE,
}

/**
 * Everything the app itself decides about what is playing, in ONE value, so a transition is one assignment. It used to be seven
 * separate flows written one after another, and a collector (which runs at every assignment on the main-immediate scope) saw the
 * new queue with the old flags: a song scrobbled before it started (#74), and a restored queue that read as "loading" (#63).
 */
internal data class Session(
    val queue: List<Track> = emptyList(),
    /** The index the app is heading for; what the screen shows while [hold] is not [Hold.WHOLE]. */
    val pendingIndex: Int = 0,
    val hold: Hold = Hold.NOTHING,
    /** True only for the real network-bound window of a play, distinct from "restored and waiting for the person to press play". */
    val loading: Boolean = false,
    /**
     * What the play/pause icon shows while the player is being re-prepared under it. `setMediaQueue` makes the real `isPlaying` drop
     * out and come back, twice, over about 0.8 s, and the icon followed it, PAUSE, PLAY, PAUSE, PLAY, PAUSE, on every song start
     * (reported on the phone, 2026-09-19). null shows the real state.
     */
    val holdingPlaying: Boolean? = null,
    /** The songs the player holds as a stream it cannot seek in, see [SongSource.seekable]. */
    val streaming: Set<String> = emptySet(),
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Why the last attempt to get a song, or the rest of the queue, ready failed: the player never got anything to fail on. */
    val loadError: String? = null,
)

/** The player's own live values, read together. */
internal data class PlayerReading(val index: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long, val error: LightAudioError?)

private data class Resolved(val index: Int, val isPending: Boolean)

/**
 * Whether the player's own index can be trusted for the current queue, or the index the app is heading for must stand in. A queue
 * the player has not been given (restored from disk) must not read the player's stale default index 0 as a queue index: it can be
 * in range by luck, and used to show whatever the player happened to hold.
 */
private fun resolve(s: Session, playerIndex: Int): Resolved {
    val pending = s.pendingIndex.coerceIn(0, (s.queue.size - 1).coerceAtLeast(0))
    return when {
        s.hold == Hold.PARTIAL -> Resolved(pending, isPending = false)
        s.hold == Hold.WHOLE && playerIndex in s.queue.indices -> Resolved(playerIndex, isPending = false)
        else -> Resolved(pending, isPending = true)
    }
}

/**
 * The one projection of [Session] and the player's live values into the [PlaybackState] every screen shows. The live flow and the
 * synchronous snapshot both call it, so they cannot disagree: they used to be two hand-written copies, and drifted (the snapshot
 * read a restored queue as loading, #63; an earlier drift showed stale art and title on every navigation, 2026-09-18).
 *
 * While the index is only pending, the player's own position, duration and playing flag still describe the previous song, so they are
 * zeroed rather than paired with the new song's title (confirmed on the phone, 2026-09-18: the playhead of the old song moved under the
 * new one's title).
 */
internal fun project(s: Session, p: PlayerReading): PlaybackState {
    val resolved = resolve(s, p.index)
    val track = s.queue.getOrNull(resolved.index)
    val shownPlaying = !resolved.isPending && (s.holdingPlaying ?: p.isPlaying)
    return PlaybackState(
        canSeek = track?.id !in s.streaming,
        queue = s.queue,
        currentIndex = resolved.index,
        isPlaying = shownPlaying,
        positionMs = if (resolved.isPending) 0L else p.positionMs,
        // A stream has no length until the server declares one or it has been read to the end, and the player reports 0 until then,
        // which showed as "0:51 / 0:00".
        durationMs = (if (resolved.isPending) 0L else p.durationMs).takeIf { it > 0L } ?: ((track?.durationSec ?: 0) * 1000L),
        shuffle = s.shuffle,
        repeatMode = s.repeatMode,
        // A real playback failure (bad stream URL, auth, unsupported format) used to look the same as "still loading".
        errorMessage = p.error?.let { "${it.kind}: ${it.diagnostic}" } ?: s.loadError,
        isLoading = s.loading && !shownPlaying,
    )
}

/**
 * The queue and the player under one owner: "this queue, this position" goes in, and how the player gets there, and the one state
 * that is reported, are decided here. Screens and the other playback behaviours (listens, track end, sleep timer, error recovery,
 * persistence) never touch the player.
 *
 * **The queue is the desired state and the player converges to it.** Every edit changes the queue at once, so a screen shows it
 * immediately, and asks for one hand-over ([handOver]): resolve every song, then swap the player onto the queue keeping the song
 * that is playing where it is. There is exactly one such routine, where there were two hand-written copies (the queue extension
 * after a play, and the rebuild after an edit) plus a third sequence in the play/pause toggle, and it reads the queue as it is when
 * it runs, so a run of edits collapses into the last one.
 *
 * **Supersession is decided here and only here.** [epoch] changes when the content is replaced (a new play, a reset): whatever was
 * in flight for the old content must not touch the queue, the player or the flags afterwards. A newer hand-over request cancels the
 * one still resolving. The swap itself, from `setMediaQueue` to the player settling, is not interrupted once it has begun (the
 * player bounces for about a second, and a second hand-over reading the position in the middle would resume from 0:00), and the next
 * one waits its turn.
 *
 * Everything runs on [scope], which must be the main thread: the SDK player's methods throw off the thread that created its
 * controller (confirmed on the phone, 2026-09-17).
 */
class PlayQueue(
    private val player: QueuePlayer,
    private val sources: SongSources,
    private val scope: CoroutineScope,
    private val isPlayable: (Track) -> Boolean,
    /** A short line for the person: "Skipped ...". */
    private val notify: (String) -> Unit,
    /** A new play began: the same song heard again is a new listen. */
    private val onNewPlay: () -> Unit,
    /** The queue or the position settled: worth saving now rather than at the next periodic save. */
    private val checkpoint: () -> Unit,
    private val timing: Timing = Timing(),
) {
    class Timing(
        /** The longest the loading icon stays up waiting for audio to start. */
        val startPlaybackWaitMs: Long = 3_000L,
        /** How long the queue's fetches are held back for a streaming song to start: fetching them at once starved it for 95 s on a weak cellular link (2026-09-23). */
        val streamStartWaitMs: Long = 60_000L,
        /** How long to wait for the player's own index to reach the target after a swap. */
        val swapIndexWaitMs: Long = 2_000L,
        /** Caps the wait for the new list's duration before seeking (a seek before it is known clamps to 0, see [QueuePlayer]). */
        val durationWaitMs: Long = 5_000L,
        val handOverResumeWaitMs: Long = 1_500L,
        /** The player bounced for about 0.8 s after a hand-over on the phone; this covers it. */
        val handOverSettleMs: Long = 800L,
        /** The longest anything waits for the saved queue to be restored. */
        val restoreWaitMs: Long = 3_000L,
    )

    private val session = MutableStateFlow(Session())
    private val art = MutableStateFlow<String?>(null)

    /** The explicit album-art hint passed to the current play, if any: set with the queue, so Now Playing does not show the song's own uncached art for a frame before the album lookup corrects it (2026-09-18). */
    val albumArtUrl: StateFlow<String?> = art.asStateFlow()

    /** The queue, for whoever keeps it saved. */
    val queue: Flow<List<Track>> = session.map { it.queue }.distinctUntilChanged()

    val playerError: StateFlow<LightAudioError?> get() = player.error
    val isPlaying: StateFlow<Boolean> get() = player.isPlaying

    /**
     * The live state: ONE flat `combine` over every input. A nested one (the player's five flows combined first, then with the
     * session) put the player's values two hops from the collector and the session's one, so a session change could be seen paired
     * with the player's previous index for a tick: the new queue with the old song's index. Flat, every input is one hop and they
     * arrive in the order they were assigned. (Six inputs is past the typed overloads, hence the array form.)
     */
    val state: Flow<PlaybackState> = combine(
        listOf<Flow<Any?>>(session, player.currentMediaItemIndex, player.isPlaying, player.positionMs, player.durationMs, player.error),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        project(v[0] as Session, PlayerReading(v[1] as Int, v[2] as Boolean, v[3] as Long, v[4] as Long, v[5] as LightAudioError?))
    }

    /**
     * The same state, read synchronously: every input is `StateFlow`-backed (confirmed in the SDK's player), so this is a real
     * value, not a guess. Seeds a fresh screen's `stateIn` so Now Playing does not flash to empty on every navigation.
     */
    fun snapshot(): PlaybackState = project(session.value, readPlayer())

    private fun readPlayer() = PlayerReading(
        player.currentMediaItemIndex.value, player.isPlaying.value, player.positionMs.value, player.durationMs.value, player.error.value,
    )

    // ---- what is in flight ----

    /** Changes when the content is replaced (a new play, a reset); see the class doc. */
    private var epoch = 0
    private var startJob: Job? = null
    private var syncJob: Job? = null
    private val handOverLock = Mutex()

    /** Set at the very top of [play]; once a real play has started this process, restoring old state over it is never correct again. */
    var hasStartedRealPlay = false
        private set

    private val restoreDone = CompletableDeferred<Unit>()

    /** The saved position, applied the first time a restored queue is played (a restore only reads, it never loads the player). */
    private var restoredPositionMs = 0L

    /** The whole queue's order captured the moment shuffle turns on, so turning it off restores it exactly. Null: not shuffled. */
    private var preShuffleOrder: List<String>? = null

    private fun supersede() {
        epoch++
        startJob?.cancel()
        syncJob?.cancel()
    }

    // ---- reading the queue ----

    /**
     * The queue index the player is really on. Everything that edits or reads the queue relative to "what is playing" (remove, move,
     * shuffle, the repeat wrap, saving) must use this rather than the player's own index, which is relative to whatever list the
     * player holds.
     */
    private fun queueIndex(): Int = resolve(session.value, player.currentMediaItemIndex.value).index

    private fun shownPlaying(): Boolean = session.value.holdingPlaying ?: player.isPlaying.value

    /**
     * How urgently [songId] is wanted right now: the playing song, one of the next few, elsewhere in the queue, or null when it is
     * not in the queue at all. Read by [FetchGate] as it schedules, so a download of the song that is playing (or about to) is
     * served ahead of the rest of the backlog.
     */
    fun priorityForSong(songId: String): FetchGate.Priority? {
        val queued = session.value.queue
        val index = queued.indexOfFirst { it.id == songId }
        if (index < 0) return null
        val current = queueIndex()
        return when {
            index == current -> FetchGate.Priority.NOW_PLAYING
            index > current && index <= current + TrackSources.UP_NEXT_COUNT -> FetchGate.Priority.UP_NEXT
            else -> FetchGate.Priority.QUEUE
        }
    }

    // ---- playing ----

    /**
     * Starts [tracks] at [requestedIndex] (the first song from there that can be played). Suspends until the first song is playing
     * or has failed; the rest of the queue is handed over in the background.
     *
     * Issue #7: `setMediaQueue` takes every item's source resolved up front (there is no lazy per-item resolution in the player), so
     * resolving the whole queue first meant a multi-track "play album" tap blocked audibly starting on downloading the entire album.
     * The starting song is loaded alone, and [handOver] fills in around it once audio is flowing.
     */
    suspend fun play(tracks: List<Track>, requestedIndex: Int, albumArtUrl: String? = null) {
        launchPlay(tracks, requestedIndex, albumArtUrl)?.join()
    }

    /**
     * [play], launched on this queue's own scope. A screen's own scope is tied to its composition, and navigating away disposes it:
     * a coroutine launched there was cancelled before `play()` ever ran, and every tap stuck at 0:00 with no log output (root cause of
     * the playback-stall bug, 2026-09-17). The synchronous part, which sets the queue and the index every screen resolves its
     * current song from, has already run when this returns, so a caller can navigate to Now Playing at once instead of after the
     * whole (potentially multi-second) load.
     */
    fun playAsync(tracks: List<Track>, requestedIndex: Int, albumArtUrl: String? = null) {
        launchPlay(tracks, requestedIndex, albumArtUrl)
    }

    private fun launchPlay(tracks: List<Track>, requestedIndex: Int, albumArtUrl: String?): Job? {
        if (tracks.isEmpty()) return null
        val requested = requestedIndex.coerceIn(0, tracks.lastIndex)
        // A song that cannot be played (its server is off or unreachable, nothing on the phone) is not started: the first one after
        // it that can be is.
        val startIndex = tracks.indices.firstOrNull { it >= requested && isPlayable(tracks[it]) } ?: requested
        if (startIndex != requested) notify("Skipped \"${tracks[requested].title}\": server not reachable")

        hasStartedRealPlay = true
        onNewPlay()
        supersede()
        // One assignment: the new queue, with nothing yet given to the player for it, and loading from this moment (so the icon shows
        // it even while the player is not ready).
        session.update { it.copy(queue = tracks, pendingIndex = startIndex, hold = Hold.NOTHING, holdingPlaying = null, loading = true, loadError = null) }
        art.value = albumArtUrl
        // Pause whatever was playing the instant the screen switches to the new song: loading can take a real, visible amount of
        // time, and leaving the old song running meant it kept audibly playing under the new song's title (reported live).
        player.pause()
        val startEpoch = epoch
        return scope.launch { startPlayback(startEpoch, tracks, startIndex) }.also { startJob = it }
    }

    private suspend fun startPlayback(startEpoch: Int, tracks: List<Track>, startIndex: Int) {
        val startTrack = tracks[startIndex]
        var streaming = false
        try {
            if (!player.awaitReady()) return
            if (startEpoch != epoch) return
            AppLogger.d(TAG, "play(): resolving starting song (index=$startIndex of ${tracks.size})")
            val source = try {
                sources.resolveStart(startTrack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The file could not be fetched (server slow or unreachable). Say so rather than leave a song that looks like it
                // is loading forever; pressing play tries again, the player holds nothing.
                AppLogger.e(TAG, "play(): could not load \"${startTrack.title}\"", e)
                updateIf(startEpoch) { it.copy(loadError = "Couldn't load \"${startTrack.title}\": ${loadFailureReason(e)}") }
                return
            }
            if (startEpoch != epoch) return // a newer play began while this resolved; it owns the player
            streaming = source is SongSource.Streamed
            player.setMediaQueue(listOf(startTrack.toItem(source)), 0)
            session.update {
                it.copy(
                    streaming = streamingOf(listOf(startTrack), listOf(source)),
                    // Only the starting song is in the player, unless it is the whole queue; the hand-over flips this.
                    hold = if (tracks.size <= 1) Hold.WHOLE else Hold.PARTIAL,
                )
            }
            player.play()
            // Keep the loading state until audio actually starts (or it fails, or 3 s pass): the player takes about half a second
            // between play() and reporting isPlaying, and the icon used to show PLAY for that gap before flipping to PAUSE.
            withTimeoutOrNull(timing.startPlaybackWaitMs) {
                combine(player.isPlaying, player.error) { playing, error -> playing || error != null }.first { it }
            }
        } finally {
            updateIf(startEpoch) { it.copy(loading = false) }
        }
        // Persist the new position right away rather than at the next periodic save: a kill right after starting a song then
        // resumes from roughly the right place instead of wherever the previous song was.
        checkpoint()
        // A lone song that is streaming goes through the same hand-over: it is swapped onto its file when that lands, which is what
        // makes it seekable (a stream started without a declared length is not) and keeps it in the cache.
        if (tracks.size > 1 || streaming) {
            requestSync(
                waitForAudio = streaming,
                onFailure = { e ->
                    // The current song is already playing; only the rest of the queue could not be fetched. Keep playing it, and say
                    // what happened: "next" still works.
                    if (tracks.size > 1) updateIf(startEpoch) { it.copy(loadError = "Couldn't load the rest of the queue: ${loadFailureReason(e)}") }
                },
            )
        }
    }

    // ---- handing the queue to the player ----

    /**
     * Asks for the player to be brought up to the queue as it is now. Cancels a hand-over still resolving: the one asked for last
     * reads the latest queue, so a run of edits ends as one.
     */
    private fun requestSync(waitForAudio: Boolean = false, onFailure: (Throwable) -> Unit) {
        val forEpoch = epoch
        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                // A song that is streaming has the connection to itself until audio is flowing.
                if (waitForAudio) awaitAudioStarted()
                handOver(forEpoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "could not bring the player up to the queue", e)
                if (forEpoch == epoch) onFailure(e)
            }
        }
    }

    /**
     * Resolves every song of the queue (whatever is not on the phone yet is fetched, concurrently, in the order [FetchGate] serves
     * them and only as many at a time as the connection can take; see [TrackSources.resolveQueue]), then swaps the player onto it,
     * putting the song that is playing back where it was.
     *
     * The position is read after the resolve, not before: the song keeps playing while its neighbours download, and a position read
     * before was replayed from the top of that stretch (a queue edit on a slow link jumped back tens of seconds). The swap carries the
     * player's known cost for any `setMediaQueue` mid-playback: a brief (about 0.25 to 0.5 s) audible pause, traded for not blocking on
     * the whole queue's download before any audio starts. `seekTo` clamps to the duration, and `setMediaQueue` resets that to 0, so the
     * seek waits for the new duration or it lands at 0:00 (Forgejo #12); a source whose duration never resolves cannot hang a hand-over
     * because that wait is capped.
     */
    private suspend fun handOver(forEpoch: Int) {
        val wanted = session.value
        val resolved = sources.resolveQueue(wanted.queue, wanted.pendingIndex) { forEpoch == epoch } ?: return
        handOverLock.withLock {
            withContext(NonCancellable) {
                if (forEpoch != epoch) return@withContext
                val target = wanted.pendingIndex
                val savedPositionMs = player.positionMs.value
                val wasPlaying = shownPlaying()
                // The player's own index is meaningless until the swap lands; the queue's is what the screen shows meanwhile.
                updateIf(forEpoch) { it.copy(hold = Hold.PARTIAL, pendingIndex = target, holdingPlaying = wasPlaying) }
                try {
                    player.setMediaQueue(wanted.queue.toItems(resolved), target)
                    updateIf(forEpoch) { it.copy(streaming = streamingOf(wanted.queue, resolved)) }
                    // Flip only once the player's own index has caught up, so the switch changes nothing on screen (issue #47).
                    withTimeoutOrNull(timing.swapIndexWaitMs) { player.currentMediaItemIndex.first { it == target } }
                    updateIf(forEpoch) { it.copy(hold = Hold.WHOLE) }
                    withTimeoutOrNull(timing.durationWaitMs) { player.durationMs.first { it > 0L } }
                    if (forEpoch == epoch) {
                        player.seekTo(savedPositionMs)
                        // The person's own pause (which clears the hold, see togglePlayPause) wins over resuming.
                        val resume = wasPlaying && session.value.holdingPlaying == true
                        if (resume) player.play()
                        // Waits out the player's own re-preparing so the real state is only shown again once it has stopped bouncing.
                        if (resume) withTimeoutOrNull(timing.handOverResumeWaitMs) { player.isPlaying.first { it } }
                        delay(timing.handOverSettleMs)
                    }
                } finally {
                    session.update { it.copy(holdingPlaying = null) }
                }
            }
        }
        if (forEpoch == epoch) checkpoint()
    }

    /** Suspends until the player reports audio playing or an error, or [Timing.streamStartWaitMs] passes. */
    private suspend fun awaitAudioStarted() {
        withTimeoutOrNull(timing.streamStartWaitMs) {
            combine(player.isPlaying, player.error) { playing, error -> playing || error != null }.first { it }
        }
    }

    /** Applies [change] unless the content it was made for has been replaced since. */
    private inline fun updateIf(forEpoch: Int, change: (Session) -> Session) {
        if (forEpoch == epoch) session.update { change(it) }
    }

    private fun streamingOf(tracks: List<Track>, resolved: List<SongSource>): Set<String> =
        tracks.filterIndexed { i, _ -> !resolved[i].seekable }.map { it.id }.toSet()

    /** What the player is given for [this]: the [source] [TrackSources] chose, with the song's own details for the lock screen and notifications. */
    private fun Track.toItem(source: SongSource): LightAudioItem = LightAudioItem(
        source = when (source) {
            is SongSource.OnPhone -> LightAudioSource.FileSource(source.file)
            is SongSource.Streamed -> LightAudioSource.UrlSource(source.url)
        },
        metadata = LightMediaMetadata(title = title, artist = artistName, album = albumName, durationMs = durationSec * 1000L),
    )

    private fun List<Track>.toItems(resolved: List<SongSource>): List<LightAudioItem> = mapIndexed { i, track -> track.toItem(resolved[i]) }

    // ---- editing the queue ----

    /**
     * Replaces the queue with [newQueue] keeping the song that is playing (at [explicitIndex] when the caller moved it, as a shuffle
     * does). The queue changes at once; the player follows through [requestSync]. If nothing has been given to the player there is
     * nothing to follow: editing a restored queue used to load the whole of it into the player (downloading every song) while
     * leaving the state as "not loaded", and to read the player's stale index 0 as the playing one.
     */
    private fun edit(newQueue: List<Track>, explicitIndex: Int? = null) {
        val before = session.value
        val currentIndex = explicitIndex ?: queueIndex().coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        session.update { it.copy(queue = newQueue, pendingIndex = currentIndex) }
        if (before.hold == Hold.NOTHING) return
        requestSync(onFailure = {
            // A song that is not downloaded could not be fetched: put the queue back as it was rather than show one the player does not hold.
            session.update { now ->
                if (now.queue === newQueue) now.copy(queue = before.queue, pendingIndex = before.pendingIndex, loadError = "Couldn't update the queue: ${loadFailureReason(it)}")
                else now.copy(loadError = "Couldn't update the queue: ${loadFailureReason(it)}")
            }
        })
    }

    /**
     * Appends [tracks] after the queue instead of replacing it. If nothing is queued yet, this is a play from index 0. A queue still
     * being restored from disk looks empty, so this waits for that first (without it, the first "add to queue" after the app
     * started took the empty branch, started playing and threw away the saved queue: a saved 35-track queue became 1, 2026-09-19).
     */
    suspend fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        awaitQueueRestored()
        val current = session.value.queue
        if (current.isEmpty()) play(tracks, 0) else edit(current + tracks)
    }

    /** [addToQueue] on this queue's own scope, for a caller that is not a suspend function and must not depend on a screen's scope. [onDone] runs after the tracks are queued. */
    fun addToQueueAsync(tracks: List<Track>, onDone: () -> Unit = {}) {
        scope.launch {
            addToQueue(tracks)
            onDone()
        }
    }

    /** Suspends until the saved queue has been restored (or restore was skipped), for at most [Timing.restoreWaitMs]. */
    suspend fun awaitQueueRestored() {
        withTimeoutOrNull(timing.restoreWaitMs) { restoreDone.await() }
    }

    /**
     * Drops every other song, past and upcoming, but leaves what is playing alone: like removal and reordering, clearing is not a
     * skip. An earlier version wiped the queue and stopped playback too, which is not what "clear queue" means while something plays.
     */
    fun clear() {
        val current = session.value.queue
        if (current.isEmpty()) return
        edit(listOf(current[queueIndex().coerceIn(0, current.lastIndex)]))
    }

    /**
     * Removes the upcoming song at [index]. Only indices after the playing one are eligible: removing "the current song" would mean
     * deciding what plays next, which is a skip, not a queue edit.
     */
    fun removeAt(index: Int) {
        val current = session.value.queue
        if (index !in current.indices || index <= queueIndex()) return
        edit(current.toMutableList().also { it.removeAt(index) })
    }

    /** Drops [serverId]'s upcoming songs, for a server that was removed and can no longer be fetched from. The playing song stays whichever server it is from. */
    fun removeServer(serverId: String) {
        val current = session.value.queue
        if (current.isEmpty()) return
        val currentIndex = queueIndex().coerceIn(0, current.lastIndex)
        val trimmed = current.filterIndexed { index, track -> index <= currentIndex || ServerScope.serverOf(track.id) != serverId }
        if (trimmed.size == current.size) return
        edit(trimmed)
    }

    /** Moves the upcoming song at [index] by [delta] slots. Both source and destination must be after the playing song, see [removeAt]. */
    fun move(index: Int, delta: Int) {
        val current = session.value.queue
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val playing = queueIndex()
        if (index <= playing || target <= playing) return
        edit(current.toMutableList().also { it.add(target, it.removeAt(index)) })
    }

    /**
     * Patches [trackId]'s favorite flag in the queue in place: metadata only, the player is not touched, so it cannot cause the
     * reorder-style pause (issue #23). The queue is a snapshot taken at play time and nothing else re-syncs an already-queued song's
     * stale flag (the Now Playing star did not update after a favorite toggle, issue #8).
     */
    fun updateFavorite(trackId: String, isFavorite: Boolean) {
        session.update { s -> s.copy(queue = s.queue.map { if (it.id == trackId) it.copy(isFavorite = isFavorite) else it }) }
    }

    // ---- shuffle and repeat ----

    /**
     * Shuffles the whole queue, already-played songs included: an already-played song can come back around. Only the song playing
     * right now stays put (it cannot retroactively un-play), moving to the front with everything else reshuffled behind it. The
     * whole queue's order is captured so turning shuffle off restores it exactly, with the playing song wherever it falls in that
     * order; a song added or removed while shuffled is dropped if gone and appended if new. Mutually exclusive with repeat-one:
     * "what is shuffled next" is meaningless when nothing advances past the current song.
     */
    fun setShuffle(enabled: Boolean) {
        if (session.value.shuffle == enabled) return
        session.update { it.copy(shuffle = enabled, repeatMode = if (enabled && it.repeatMode == RepeatMode.REPEAT_TRACK) RepeatMode.OFF else it.repeatMode) }
        applyShuffle(enabled)
        checkpoint()
    }

    /** Repeat-one and shuffle are mutually exclusive: switching into repeat-one turns shuffle off and restores the queue's order. */
    fun setRepeatMode(mode: RepeatMode) {
        val turnsShuffleOff = mode == RepeatMode.REPEAT_TRACK && session.value.shuffle
        session.update { it.copy(repeatMode = mode, shuffle = if (turnsShuffleOff) false else it.shuffle) }
        if (turnsShuffleOff) applyShuffle(false)
        checkpoint()
    }

    private fun applyShuffle(enabled: Boolean) {
        val current = session.value.queue
        if (current.isEmpty()) return
        val currentIndex = queueIndex().coerceIn(0, current.lastIndex)
        val currentTrack = current[currentIndex]
        val rest = current.filterIndexed { i, _ -> i != currentIndex }
        if (rest.isEmpty()) return // nothing to shuffle or restore: one song in the queue
        if (enabled) {
            preShuffleOrder = current.map { it.id }
            edit(listOf(currentTrack) + rest.shuffled(), explicitIndex = 0)
        } else {
            val order = preShuffleOrder ?: return // shuffle was never really applied: nothing to restore
            preShuffleOrder = null
            val byId = current.associateBy { it.id }
            val restored = order.mapNotNull { byId[it] } + current.filter { it.id !in order }
            edit(restored, explicitIndex = restored.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0))
        }
    }

    // ---- transport ----

    /**
     * Ordinary pause/play toggle, except when the player holds nothing for the queue (right after a restore, issue #27): the first
     * press has to load it, with the saved position applied afterwards, rather than pause/play a player that has nothing loaded.
     */
    fun togglePlayPause() {
        val s = session.value
        if (s.queue.isNotEmpty() && s.hold == Hold.NOTHING) {
            val startIndex = s.pendingIndex.coerceIn(0, s.queue.lastIndex)
            val resumePositionMs = restoredPositionMs
            restoredPositionMs = 0L
            scope.launch {
                play(s.queue, startIndex, art.value)
                if (resumePositionMs > 0L) {
                    withTimeoutOrNull(timing.durationWaitMs) { player.durationMs.first { it > 0L } }
                    player.seekTo(resumePositionMs)
                }
            }
            return
        }
        when {
            // What the person sees, not the player's own flag: for about a second after a hand-over the player reports "not
            // playing" while the icon still shows playing, and a press then took the "play" branch instead of pausing.
            shownPlaying() -> {
                // The person's own pause wins over any hand-over still holding the icon.
                session.update { it.copy(holdingPlaying = null) }
                player.pause()
                checkpoint()
            }
            player.error.value != null -> {
                // After a playback error the player sits idle until it is prepared again, so a bare play() does nothing (pressing
                // play left the error on screen for good, issue #50). Start the current song again, as tapping it in the queue does.
                val now = snapshot()
                if (now.currentIndex in now.queue.indices) playAsync(now.queue, now.currentIndex, art.value) else player.play()
            }
            else -> player.play()
        }
    }

    /** Pauses if playing, and says whether it did: a sleep timer firing after a manual pause stays paused. */
    fun pauseIfPlaying(): Boolean {
        if (!player.isPlaying.value) return false
        player.pause()
        checkpoint()
        return true
    }

    fun pause() = player.pause()

    /** Starts the playing song again from 0:00 (repeat-one). */
    fun restartTrack() {
        player.seekTo(0)
        if (!player.isPlaying.value) player.play()
    }

    // Ignored while the song is a stream the player cannot seek in (the screen dims the buttons; this covers a headset button or
    // anything else that gets here), rather than restarting it or doing nothing to no visible effect.
    fun skipBack() { if (currentSongCanSeek()) player.skipBack() }
    fun skipForward() { if (currentSongCanSeek()) player.skipForward() }

    private fun currentSongCanSeek(): Boolean = session.value.let { it.queue.getOrNull(queueIndex())?.id !in it.streaming }

    fun seekTo(ms: Long) = player.seekTo(ms)

    /**
     * The player's own skip knows nothing about repeat-all, so "next" on the last song did nothing (reported live, 2026-09-18): it
     * wraps to the start. And while the player holds only part of the queue (the song that is starting, before the rest is handed
     * over, which can take a minute on a weak link) its own skip has nothing to skip to, so "next" did nothing at all: the next
     * song is played instead.
     */
    fun skipToNext() {
        val s = session.value
        val index = queueIndex()
        when {
            s.repeatMode == RepeatMode.REPEAT_QUEUE && index == s.queue.lastIndex -> playAsync(s.queue, 0, art.value)
            s.hold != Hold.WHOLE -> if (index + 1 in s.queue.indices) playAsync(s.queue, index + 1, art.value)
            else -> player.skipToNext()
        }
    }

    /** The other direction: "previous" on the first song under repeat-all wraps to the last, and while the player holds only part of the queue it goes back a song, or restarts this one when it is well into it, as the player's own skip does. */
    fun skipToPrevious() {
        val s = session.value
        val index = queueIndex()
        when {
            s.repeatMode == RepeatMode.REPEAT_QUEUE && index == 0 -> playAsync(s.queue, s.queue.lastIndex, art.value)
            s.hold != Hold.WHOLE -> {
                val wellIn = player.positionMs.value > PREVIOUS_RESTARTS_AFTER_MS && currentSongCanSeek()
                if (wellIn || index == 0) player.seekTo(0) else playAsync(s.queue, index - 1, art.value)
            }
            else -> player.skipToPrevious()
        }
    }

    /**
     * Jumps to the song at [index] of the queue (tapping a row in the queue view, issue #28). The player has no "jump to queue
     * position N" (light-sdk#217), so this is a play of the same queue at a new index, from 0:00.
     */
    fun jumpToAsync(index: Int) {
        val current = session.value.queue
        if (index !in current.indices) return
        playAsync(current, index, art.value)
    }

    suspend fun jumpTo(index: Int) {
        val current = session.value.queue
        if (index !in current.indices) return
        play(current, index, art.value)
    }

    // ---- saving, restoring, resetting ----

    /**
     * Puts back what was saved: a restore only reads, it never touches the player and fetches nothing, so every screen can show the
     * restored song, art and modes at once without a cold-start network fetch nobody asked for. The player is loaded when the person
     * presses play ([togglePlayPause]).
     */
    fun restore(restored: RestoredPlayback) {
        session.update { it.copy(queue = restored.tracks, pendingIndex = restored.index, shuffle = restored.shuffle, repeatMode = restored.repeatMode) }
        art.value = restored.albumArtUrl
        restoredPositionMs = restored.positionMs
    }

    /** Tells [awaitQueueRestored] that restoring has finished or was skipped. */
    fun restoreFinished() {
        restoreDone.complete(Unit)
    }

    /** What to save right now, or null while nothing is loaded: there is nothing new to save, and the player's own index and position are not the queue's yet. */
    fun saveable(): PlaybackStateRepository.Saved? {
        val s = session.value
        if (s.hold == Hold.NOTHING || s.queue.isEmpty()) return null
        return PlaybackStateRepository.Saved(
            currentIndex = queueIndex().coerceIn(0, s.queue.lastIndex),
            positionMs = player.positionMs.value,
            shuffle = s.shuffle,
            repeatMode = s.repeatMode,
            albumArtUrl = art.value,
        )
    }

    /** Shows [text] as the reason loading failed, for a failure nothing else caught. */
    fun noteFailure(text: String) {
        session.update { it.copy(loadError = text) }
    }

    /**
     * Stops playback and forgets everything, for "Clear all local data": the playing song's file may have just been deleted, so
     * playing on would error out or keep going from an already-buffered chunk that can never be seeked or replayed. It supersedes
     * what is in flight, so a hand-over started before the reset cannot bring the queue back (#64: a run documented as 30 to 100
     * seconds could put the queue back after "Clear all local data").
     */
    fun reset() {
        supersede()
        player.pause()
        session.value = Session()
        art.value = null
        preShuffleOrder = null
        restoredPositionMs = 0L
    }

    fun release() = player.release()

    private companion object {
        const val TAG = "PlayQueue"

        /** The player's own "previous" restarts the song instead of going back once it is this far in. */
        const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L
    }
}
