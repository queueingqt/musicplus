package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.RepeatMode

/** What to do as the current track reaches its end. */
enum class TrackEndAction {
    /** Repeat-one: start the same track again. */
    RESTART_TRACK,

    /** Repeat-all on the last track: play the queue again from the top (ExoPlayer already advances mid-queue). */
    WRAP_QUEUE,

    /** The sleep timer was set to "end of current track". */
    SLEEP,
}

/**
 * Decides what happens as a track reaches its end. The SDK player has no track-completion or end-of-queue signal (filed upstream as
 * light-sdk#218), so this estimates "about to end" from position and duration: position updates every 250 ms, so a 500 ms window
 * gives a couple of ticks of margin to act before the track really finishes and ExoPlayer advances on its own. The cost is losing
 * the last half second of a repeat-one loop, which reads as far less jarring than letting it advance first and snapping back.
 *
 * One decision for everything that acts at a track's end (repeat wrap-around and the sleep timer's "end of track" mode used to be two
 * watchers with a copy of the same test each, and both could fire at once). When both apply, sleep wins: someone who asked to sleep
 * at the end of the song does not want it restarted. If light-sdk#218 lands, replace the estimate with the real event and keep the policy.
 *
 * A reading near the end only counts for a song that has been seen playing away from its end: for one tick after a change of song
 * the state carries the new song's index and the previous song's position, which is "near the end" whenever that song was ending. Taken
 * at face value, repeat-all wrapped the queue the moment the song before the last one ended, and the last song was never played (#78).
 *
 * Pure: it is fed [PlaybackState] and returns an action.
 */
class TrackEnd(private val windowMs: Long = NEAR_END_WINDOW_MS) {
    private var firedIndex = -1

    /** Which song, at which queue index, has been seen away from its end; a different one has not been heard yet. */
    private var heardSong: Pair<Int, String?>? = null

    /** The action to take now, if this state is the first to find the current track inside its final window; else null. */
    fun observe(s: PlaybackState, repeat: RepeatMode, sleepAtEndOfTrack: Boolean): TrackEndAction? {
        if (!s.isPlaying || s.durationMs <= 0L) return null
        val song = s.currentIndex to s.currentTrack?.id
        val remainingMs = s.durationMs - s.positionMs
        if (remainingMs !in 0..windowMs) {
            // Re-armed the instant the track is not near its end, so a restarted or wrapped track fires again only after being played
            // nearly all the way through once more, never as a same-position re-fire.
            firedIndex = -1
            heardSong = song
            return null
        }
        if (heardSong != song) return null // the position may still be the previous song's
        if (firedIndex == s.currentIndex) return null
        firedIndex = s.currentIndex
        return when {
            sleepAtEndOfTrack -> TrackEndAction.SLEEP
            repeat == RepeatMode.REPEAT_TRACK -> TrackEndAction.RESTART_TRACK
            repeat == RepeatMode.REPEAT_QUEUE && s.currentIndex == s.queue.lastIndex -> TrackEndAction.WRAP_QUEUE
            else -> null
        }
    }

    companion object {
        const val NEAR_END_WINDOW_MS = 500L
    }
}
