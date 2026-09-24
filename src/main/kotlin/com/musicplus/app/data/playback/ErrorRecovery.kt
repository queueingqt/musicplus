package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode
import com.musicplus.app.Track

/** What the player said went wrong: whether it was a source (I/O) failure, and its diagnostic text. */
data class PlayerFault(val isSourceError: Boolean, val diagnostic: String)

/** Where playback goes when the current song cannot be played. */
sealed interface SkipMove {
    /** The next playable song after the current one. */
    data class To(val index: Int) : SkipMove

    /** Repeat-all wrap-around: the first playable song. */
    data class WrapTo(val index: Int) : SkipMove

    /** Nothing left in the queue that plays, or every song has been tried: stop. */
    data object Stop : SkipMove
}

sealed interface Recovery {
    data object None : Recovery

    /** The song cannot be played (its server is off or unreachable and the phone has no copy): say so and move on. */
    data class Skip(val track: Track, val move: SkipMove) : Recovery

    /** Play the current song again once, after [RETRY_DELAY_MS]. */
    data object Retry : Recovery
}

const val RETRY_DELAY_MS = 750L

/**
 * What to do about a player error (issues #47 and #50). The SDK's error only ever reached the screen as Now Playing's "Playback
 * error: ..." line, and an `ERROR_CODE_IO_UNSPECIFIED` that hit as a track started left no trace. A source (I/O) failure at the
 * very start of a track has been seen to clear on a fresh attempt, so the current track is retried once: at most once per
 * [RETRY_COOLDOWN_MS] per track, and only within the first [RETRY_MAX_POSITION_MS] of it, so a failure mid-song never yanks the
 * listener back to 0:00. A file that is gone (its download was removed while the song sat in the queue) is not a flaky start and
 * `positionMs` says nothing about it (the player can still be reporting the previous item's), so it is always retried once:
 * re-resolving the track fetches it again.
 *
 * A song whose server is off or unreachable, with nothing on the phone, is skipped instead. A run of unplayable songs stops the
 * queue rather than looping forever ([skipStreak] counts them; [onPlaying] clears it).
 *
 * Pure: the wall clock and the availability rule are passed in, so it is tested without a player.
 */
class ErrorRecovery {
    private val lastRetryAtMs = HashMap<String, Long>()
    private var skipStreak = 0

    /** A network failure at the start of a song says its server cannot be reached, even before a request of ours has noticed. */
    fun indicatesUnreachableServer(fault: PlayerFault): Boolean =
        fault.isSourceError && ("NETWORK_CONNECTION" in fault.diagnostic || "TIMEOUT" in fault.diagnostic)

    /** Audio is playing again: the streak of skipped songs is over. */
    fun onPlaying() {
        skipStreak = 0
    }

    fun decide(fault: PlayerFault, s: PlaybackState, repeat: RepeatMode, playable: (Track) -> Boolean, nowMs: Long): Recovery {
        val track = s.currentTrack
        if (!fault.isSourceError || track == null) return Recovery.None
        if (!playable(track)) return Recovery.Skip(track, skipMove(s, repeat, playable))

        val fileGone = "FILE_NOT_FOUND" in fault.diagnostic
        if (!fileGone && s.positionMs > RETRY_MAX_POSITION_MS) return Recovery.None
        val last = lastRetryAtMs[track.id]
        if (last != null && nowMs - last < RETRY_COOLDOWN_MS) return Recovery.None
        lastRetryAtMs[track.id] = nowMs
        return Recovery.Retry
    }

    private fun skipMove(s: PlaybackState, repeat: RepeatMode, playable: (Track) -> Boolean): SkipMove {
        skipStreak++
        val next = s.queue.indices.firstOrNull { it > s.currentIndex && playable(s.queue[it]) }
        val wrapTo = if (next == null && repeat == RepeatMode.REPEAT_QUEUE) s.queue.indexOfFirst(playable).takeIf { it >= 0 } else null
        if ((next == null && wrapTo == null) || skipStreak > s.queue.size) {
            skipStreak = 0
            return SkipMove.Stop
        }
        return if (next != null) SkipMove.To(next) else SkipMove.WrapTo(wrapTo!!)
    }

    companion object {
        /** Only an error this early in a track is retried automatically. */
        const val RETRY_MAX_POSITION_MS = 3_000L

        /** The same track is retried at most once per this long. */
        const val RETRY_COOLDOWN_MS = 30_000L
    }
}
