package com.musicplus.app.data

import com.musicplus.app.NoteModal
import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.musicplus.app.data.playback.AlbumArtHint
import com.musicplus.app.data.playback.ErrorRecovery
import com.musicplus.app.data.playback.JellyfinPlayReportSink
import com.musicplus.app.data.playback.LightQueuePlayer
import com.musicplus.app.data.playback.ListenReporting
import com.musicplus.app.data.playback.PlayQueue
import com.musicplus.app.data.playback.PlaybackPersistence
import com.musicplus.app.data.playback.PlayerFault
import com.musicplus.app.data.playback.RETRY_DELAY_MS
import com.musicplus.app.data.playback.Recovery
import com.musicplus.app.data.playback.ScrobbleSink
import com.musicplus.app.data.playback.SkipMove
import com.musicplus.app.data.playback.SleepTimer
import com.musicplus.app.data.playback.StreamCache
import com.musicplus.app.data.playback.TrackEnd
import com.musicplus.app.data.playback.TrackEndAction
import com.musicplus.app.data.playback.TrackSources
import com.musicplus.app.data.playback.loadFailureReason
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioPlayback
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What screens talk to for playback, and where the playback behaviours are wired to the queue: [PlayQueue] owns the queue and the one
 * SDK player behind it (so nothing here touches the player), and each behaviour is its own module in `data/playback`: listens
 * ([ListenReporting]), the end of a song ([TrackEnd]), the sleep timer ([SleepTimer]), recovery from a player error
 * ([ErrorRecovery]) and saving across a restart ([PlaybackPersistence]).
 *
 * Built around `LightAudioPlayback.Detached`: per the SDK reference notes, that is what survives navigating away from PlayerScreen or
 * the phone locking, and only one detached handle may exist at a time, so this MUST be a single shared instance (see
 * [PlaybackRepositoryHolder]), never constructed fresh per screen.
 */
class PlaybackRepository(
    audio: LightAudio,
    private val apiHolder: ApiHolder,
    streamCache: StreamCache,
    private val libraryRepository: LibraryRepository,
    queueDao: QueueDao,
    playbackStateRepository: PlaybackStateRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val connectivity: LightConnectivity,
) {
    // Process-lifetime, same reasoning as AppGraph's own appScope: this repository is itself a process-lifetime singleton (see
    // PlaybackRepositoryHolder), so nothing here needs a narrower scope tied to any particular screen's composition.
    //
    // Main, not Default: every path through this scope eventually calls the player (pause/play/setMediaQueue/seekTo), which wraps a
    // media3 MediaController; those methods throw IllegalStateException off the thread that created the controller (main). Confirmed
    // on-device, 2026-09-17: a launch crashed on exactly this ("MediaController method is called from a wrong thread") the first
    // time anything ran on this scope with Dispatchers.Default. Suspend calls that do real network I/O stay non-blocking regardless:
    // Ktor's CIO engine dispatches its own socket I/O internally rather than blocking the caller's dispatcher.
    //
    // The exception handler is the last line of defence: a failure that reaches it (a song that could not be fetched, say) is logged
    // and shown as an error line instead of taking the whole app down. Before it existed, an uncaught download timeout in a coroutine
    // on this scope crashed the app 8 times in one evening (2026-09-19).
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e ->
                AppLogger.e("PlaybackRepository", "uncaught in a playback coroutine", e)
                playQueue.noteFailure("Couldn't load audio: ${loadFailureReason(e)}")
            },
    )

    /** Decides, for every song the player is given, whether it plays from a file or streams, and whether it can seek (#59). */
    private val sources: TrackSources = TrackSources(
        cache = streamCache,
        apiFor = { apiHolder.forId(it.id) },
        serverUsable = { TrackAvailability.serverUsable(it) },
        priorityOf = { playQueue.priorityForSong(it) },
        streamKbps = { currentStreamMaxBitRateKbps() },
        onWifi = { connectivity.currentStatus.isWifi },
    )

    // The queue and the player under one owner (#62).
    private val playQueue: PlayQueue = PlayQueue(
        player = LightQueuePlayer(audio.newPlayer(playback = LightAudioPlayback.Detached)),
        sources = sources,
        scope = scope,
        isPlayable = { TrackAvailability.isPlayable(it) },
        notify = { NoteModal.show(it) },
        onNewPlay = { listens.beginPlay() },
        checkpoint = { scope.launch { persistScalarStateIfLoaded() } },
    )

    val state: Flow<PlaybackState> = playQueue.state

    /** The album-art hint of the current play, if any: applies only to songs of the album it was given for. */
    val albumArtHint: StateFlow<AlbumArtHint?> = playQueue.albumArtHint

    /** The state, read synchronously: seeds a fresh screen's `stateIn` so Now Playing does not flash to empty on every navigation. */
    fun currentSnapshot(): PlaybackState = playQueue.snapshot()

    // The sleep timer, and the "the song is about to end" decision that both it (its end-of-song mode) and repeat mode act on.
    val sleepTimer = SleepTimer(scope, onSleep = { pauseForSleepTimer() })
    private val trackEnd = TrackEnd()
    private val trackEndWatcher = scope.launch {
        state.collect { s ->
            when (trackEnd.observe(s, s.repeatMode, sleepTimer.endOfTrackArmed)) {
                TrackEndAction.RESTART_TRACK -> playQueue.restartTrack()
                TrackEndAction.WRAP_QUEUE -> playQueue.playAsync(s.queue, 0)
                TrackEndAction.SLEEP -> sleepTimer.fire()
                null -> {}
            }
        }
    }

    /** The sleep timer's only effect: pause, and only if actually playing, so a timer firing after a manual pause stays paused. */
    private fun pauseForSleepTimer() {
        playQueue.pauseIfPlaying()
    }

    // Reports listening (scrobbles, a Jellyfin server's play history). What counts as a listen is ListenTracker's business and where it
    // is reported is the sinks': see data/playback.
    private val listens: ListenReporting = ListenReporting(
        scope = scope,
        sinks = listOf(
            ScrobbleSink(apiHolder, libraryRepository) { appSettingsRepository.scrobblingEnabled.first() },
            JellyfinPlayReportSink(apiHolder),
        ),
    ).also { it.start(state) }

    // Player errors (issues #47/#50): what to retry and what to skip is ErrorRecovery's policy; this carries it out.
    private val errorRecovery = ErrorRecovery()
    private val playerErrorWatcher = scope.launch {
        playQueue.playerError.collect { err ->
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
            when (val recovery = errorRecovery.decide(fault, s, s.repeatMode, { TrackAvailability.isPlayable(it) }, System.currentTimeMillis())) {
                Recovery.None -> {}
                is Recovery.Skip -> skipUnavailable(recovery, s)
                Recovery.Retry -> {
                    AppLogger.d("PlaybackRepository", "auto-retrying ${track?.id} once after ${err.diagnostic}")
                    delay(RETRY_DELAY_MS)
                    if (s.currentIndex in s.queue.indices) playQueue.playAsync(s.queue, s.currentIndex)
                }
            }
        }
    }

    private val skipStreakReset = scope.launch { playQueue.isPlaying.collect { if (it) errorRecovery.onPlaying() } }

    /** The song that just failed cannot be played (its server is off or unreachable and the phone has no copy): say so briefly and carry out the move. */
    private fun skipUnavailable(skip: Recovery.Skip, s: PlaybackState) {
        when (val move = skip.move) {
            SkipMove.Stop -> {
                AppLogger.d("PlaybackRepository", "nothing left to play in the queue, stopping")
                playQueue.pause()
                NoteModal.show("Server not reachable")
            }
            is SkipMove.To, is SkipMove.WrapTo -> {
                AppLogger.d("PlaybackRepository", "skipping \"${skip.track.title}\" (${skip.track.id}): its server is not reachable")
                NoteModal.show("Skipped \"${skip.track.title}\": server not reachable")
                if (move is SkipMove.To) playQueue.jumpToAsync(move.index) else playQueue.playAsync(s.queue, (move as SkipMove.WrapTo).index)
            }
        }
    }

    // Saving and restoring the queue and playback state across a restart is PlaybackPersistence's; this only applies what it restored.
    // Restore must finish before the queue writer starts (see its doc), and both run in this one coroutine, in that order.
    private val persistence = PlaybackPersistence(queueDao, playbackStateRepository) { libraryRepository.getTracksByIds(it) }

    init {
        scope.launch {
            try {
                persistence.restore(stillWanted = { !playQueue.hasStartedRealPlay })?.let { playQueue.restore(it) }
            } finally {
                playQueue.restoreFinished()
            }
            persistence.keepQueueSaved(playQueue.queue)
        }
    }

    private val statePersistenceWatcher = persistence.savePeriodically(scope) { playQueue.saveable() }

    private suspend fun persistScalarStateIfLoaded() {
        playQueue.saveable()?.let { persistence.save(it) }
    }

    // ---- what screens call ----

    /** See [PlayQueue.play]. */
    suspend fun play(tracks: List<Track>, requestedIndex: Int, albumArtUrl: String? = null) = playQueue.play(tracks, requestedIndex, albumArtUrl)

    /** See [PlayQueue.playAsync]: what screens call, never their own scope. */
    fun playAsync(tracks: List<Track>, startIndex: Int, albumArtUrl: String? = null) = playQueue.playAsync(tracks, startIndex, albumArtUrl)

    suspend fun addToQueue(tracks: List<Track>) = playQueue.addToQueue(tracks)
    fun addToQueueAsync(tracks: List<Track>, onDone: () -> Unit = {}) = playQueue.addToQueueAsync(tracks, onDone)

    /** Anything that inspects the queue on behalf of the person has to wait for this, or right after launch it sees an empty queue. */
    suspend fun awaitQueueRestored() = playQueue.awaitQueueRestored()

    fun clearQueue() = playQueue.clear()
    fun removeFromQueue(index: Int) = playQueue.removeAt(index)
    fun removeServerFromQueue(serverId: String) = playQueue.removeServer(serverId)
    fun moveQueueItem(index: Int, delta: Int) = playQueue.move(index, delta)
    fun updateTrackFavorite(trackId: String, isFavorite: Boolean) = playQueue.updateFavorite(trackId, isFavorite)

    fun togglePlayPause() = playQueue.togglePlayPause()
    fun skipBack() = playQueue.skipBack()
    fun skipForward() = playQueue.skipForward()
    fun skipToNext() = playQueue.skipToNext()
    fun skipToPrevious() = playQueue.skipToPrevious()
    fun seekTo(ms: Long) = playQueue.seekTo(ms)
    suspend fun jumpTo(index: Int) = playQueue.jumpTo(index)
    fun jumpToAsync(index: Int) = playQueue.jumpToAsync(index)
    fun setShuffle(enabled: Boolean) = playQueue.setShuffle(enabled)
    fun setRepeatMode(mode: RepeatMode) = playQueue.setRepeatMode(mode)

    /** How urgently [songId] is wanted right now, for [FetchGate]: see [PlayQueue.priorityForSong]. */
    fun priorityForSong(songId: String) = playQueue.priorityForSong(songId)

    fun release() = playQueue.release()

    /**
     * Resets every in-memory playback field to empty, used by LocalDataRepository.clearAll() once it has wiped the persisted queue,
     * cached audio and cached art out from under whatever this repository still has loaded. Stops playback first (see
     * [PlayQueue.reset], which also supersedes anything still being handed to the player).
     */
    fun resetInMemoryState() {
        playQueue.reset()
        sleepTimer.cancel()
    }

    /**
     * Picks Wi-Fi or cellular quality based on the connection actually in use right now: read fresh on every call (not cached or
     * observed), same reasoning as reading [apiHolder] fresh each time, since it only needs the answer at the exact moment a song is
     * about to be fetched. [LightConnectivity.currentStatus] requires ACCESS_NETWORK_STATE, already granted.
     *
     * Songs are asked for at this cap rather than always as the original file (issue #7: a huge lossless source turned "just the
     * starting song" back into a multi-second block).
     */
    private suspend fun currentStreamMaxBitRateKbps(): Int? =
        if (connectivity.currentStatus.isWifi) {
            appSettingsRepository.streamQualityWifi.first()
        } else {
            appSettingsRepository.streamQualityCellular.first()
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
    ): PlaybackRepository =
        instance ?: synchronized(this) {
            instance ?: PlaybackRepository(
                audio = com.thelightphone.sdk.audio.DefaultLightAudio(sealedActivity),
                apiHolder = graph.apiHolder,
                streamCache = graph.streamCache,
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
    PlaybackRepositoryHolder.get(activity, AppGraph.from(lightContext))
