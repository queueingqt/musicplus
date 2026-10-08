package com.musicplus.app.data.playback

import com.musicplus.app.Track
import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.ServerScope
import com.thelightphone.sdk.audio.LightAudioErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Carries out what [ErrorRecovery] decides about a player error (issues #47 and #50): retry the song once, or skip one that cannot be
 * played and say so. The policy is [ErrorRecovery]'s and where to go is [Advance]'s; this is the glue that used to sit in
 * `PlaybackRepository`, where nothing could drive it without a real player. It works on a [PlayQueue], so a test drives it with the fake one.
 *
 * A retry is launched, so a later error is not held up behind the delay, and it reads the queue again once the delay is over: a jump, an
 * edit or a new play made in the meantime is the person's, and replaying the queue as it was would overwrite it.
 */
class PlayerErrorHandler(
    private val scope: CoroutineScope,
    private val queue: PlayQueue,
    private val isPlayable: (Track) -> Boolean,
    private val onServerUnreachable: (serverId: String) -> Unit,
    private val notify: (String) -> Unit,
    private val recovery: ErrorRecovery = ErrorRecovery(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    fun start() {
        scope.launch { queue.playerError.collect { err -> if (err != null) handle(err.kind, err.diagnostic, err.itemIndex) } }
        // Audio playing again ends the run of skipped songs.
        scope.launch { queue.isPlaying.collect { if (it) recovery.onPlaying() } }
    }

    private fun handle(kind: LightAudioErrorKind, diagnostic: String, itemIndex: Int?) {
        val s = queue.snapshot()
        val track = s.currentTrack
        AppLogger.e(
            TAG,
            "player error $kind: $diagnostic (item $itemIndex); track=${track?.id} \"${track?.title}\" queueIndex=${s.currentIndex} of ${s.queue.size} position=${s.positionMs}ms",
        )
        val fault = PlayerFault(isSourceError = kind == LightAudioErrorKind.Source, diagnostic = diagnostic)
        if (track != null && recovery.indicatesUnreachableServer(fault)) ServerScope.serverOf(track.id)?.let(onServerUnreachable)
        when (val decision = recovery.decide(fault, s, s.repeatMode, isPlayable, nowMs())) {
            Recovery.None -> {}
            is Recovery.Skip -> skip(decision, s.queue)
            Recovery.Retry -> {
                AppLogger.d(TAG, "auto-retrying ${track?.id} once after $diagnostic")
                scope.launch {
                    delay(retryDelayMs)
                    val now = queue.snapshot()
                    if (ErrorRecovery.retryStillApplies(s, now)) queue.playAsync(now.queue, now.currentIndex)
                }
            }
        }
    }

    /** The song that just failed cannot be played (its server is off or unreachable and the phone has no copy): say so briefly and carry out the move. */
    private fun skip(skip: Recovery.Skip, songs: List<Track>) {
        when (val move = skip.move) {
            SkipMove.Stop -> {
                AppLogger.d(TAG, "nothing left to play in the queue, stopping")
                queue.pause()
                notify("Server not reachable")
            }
            is SkipMove.To -> {
                AppLogger.d(TAG, "skipping \"${skip.track.title}\" (${skip.track.id}): its server is not reachable")
                notify("Skipped \"${skip.track.title}\": server not reachable")
                queue.jumpToAsync(move.index)
            }
            is SkipMove.WrapTo -> {
                AppLogger.d(TAG, "skipping \"${skip.track.title}\" (${skip.track.id}): its server is not reachable")
                notify("Skipped \"${skip.track.title}\": server not reachable")
                queue.playAsync(songs, move.index)
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackRepository"
    }
}
