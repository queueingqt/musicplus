package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import java.util.UUID

/** One track being heard in one play. [sessionId] tells concurrent listens apart to a server that wants to know. */
data class Listen(val trackId: String, val sessionId: String)

sealed interface ListenEvent {
    /** The listen began: the track is current and audibly playing. This is "now playing". */
    data class Started(val listen: Listen, val positionMs: Long) : ListenEvent

    /** The listen has been heard for long enough to count (the Last.fm rule: half the track or four minutes, and never under 30 s). */
    data class Counted(val listen: Listen) : ListenEvent

    /** The track stopped being the current one (the queue moved on or was cleared, or a new play began); [positionMs] is where it last was. */
    data class Ended(val listen: Listen, val positionMs: Long) : ListenEvent
}

/**
 * What "a listen" is, defined once for everything that reports listening (scrobbling, a Jellyfin server's play history).
 *
 * A listen is a track heard in one *play*: it is identified by the track's id and by the play it belongs to ([beginPlay]
 * starts a new one), never by the track's queue index. The index is the wrong identity: a song played on its own is
 * always index 0, so keying on it silently dropped every later single-song play from scrobbling (#58), and clearing or
 * shuffling the queue moves the playing track to another index without it being a new listen.
 *
 * Pure: it is fed [PlaybackState] and returns events, so it is tested with states and no player.
 */
class ListenTracker(private val newSessionId: () -> String = { UUID.randomUUID().toString() }) {
    private var epoch = 0
    private var current: InProgress? = null

    private class InProgress(val listen: Listen, val epoch: Int) {
        var lastPositionMs = 0L
        var counted = false
    }

    /** A new play began (a tap on a song, a queue jump): the same song heard again is a new listen. */
    fun beginPlay() {
        epoch++
    }

    fun observe(s: PlaybackState): List<ListenEvent> {
        val events = ArrayList<ListenEvent>(2)
        val track = s.currentTrack

        current?.let { inProgress ->
            if (track?.id != inProgress.listen.trackId || epoch != inProgress.epoch) {
                events += ListenEvent.Ended(inProgress.listen, inProgress.lastPositionMs)
                current = null
            } else {
                inProgress.lastPositionMs = s.positionMs
            }
        }

        if (track == null || !s.isPlaying || s.durationMs <= 0L) return events

        val inProgress = current ?: InProgress(Listen(track.id, newSessionId()), epoch).also {
            it.lastPositionMs = s.positionMs
            current = it
            events += ListenEvent.Started(it.listen, s.positionMs)
        }

        if (!inProgress.counted && s.durationMs >= MIN_COUNTED_DURATION_MS && s.positionMs >= thresholdMs(s.durationMs)) {
            inProgress.counted = true
            events += ListenEvent.Counted(inProgress.listen)
        }
        return events
    }

    companion object {
        /** Last.fm's own "don't scrobble anything shorter than this" rule. */
        const val MIN_COUNTED_DURATION_MS = 30_000L

        /** Last.fm's own "half the track, or this, whichever is smaller" threshold. */
        const val MAX_COUNTED_THRESHOLD_MS = 240_000L

        fun thresholdMs(durationMs: Long): Long = minOf(durationMs / 2, MAX_COUNTED_THRESHOLD_MS)
    }
}
