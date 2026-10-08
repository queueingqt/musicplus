package com.musicplus.app.data.playback

import com.musicplus.app.RepeatMode
import com.musicplus.app.Track

/** Where the queue goes from a song. */
sealed interface Step {
    /** Straight on: the next song that plays. */
    data class To(val index: Int) : Step

    /** Repeat-all came round: the first song that plays again. */
    data class WrapTo(val index: Int) : Step
}

/**
 * Which song comes next, in one place. A song ending, the person pressing next, and a song that cannot be played all ask the same question,
 * and used to answer it three times: two wrapped to the top of the queue whatever it held, the third to the first song that actually plays.
 * Now they agree: the next song that [playable] says plays, and once the end is reached, under repeat-all, the first one that does.
 *
 * Pure: fed the queue and where it is, returns where to go (or null when there is nowhere).
 */
object Advance {
    /** The step after the song at [index]. */
    fun after(queue: List<Track>, index: Int, repeat: RepeatMode, playable: (Track) -> Boolean): Step? {
        val next = queue.indices.firstOrNull { it > index && playable(queue[it]) }
        if (next != null) return Step.To(next)
        if (repeat != RepeatMode.REPEAT_QUEUE) return null
        return queue.indexOfFirst(playable).takeIf { it >= 0 }?.let { Step.WrapTo(it) }
    }

    /** The step before the song at [index]: the previous song that plays, and under repeat-all from the first song, the last one that does. */
    fun before(queue: List<Track>, index: Int, repeat: RepeatMode, playable: (Track) -> Boolean): Step? {
        val previous = queue.indices.lastOrNull { it < index && playable(queue[it]) }
        if (previous != null) return Step.To(previous)
        if (repeat != RepeatMode.REPEAT_QUEUE) return null
        return queue.indices.lastOrNull { playable(queue[it]) }?.let { Step.WrapTo(it) }
    }
}
