package com.musicplus.app.data.playback

import com.musicplus.app.Track
import com.musicplus.app.data.ServerScope
import kotlin.random.Random

/** A queue edit's result: the new list, and where the playing song sits in it when the edit moved it (null: where it was, kept as close as the new list allows). */
class QueueEdit(val queue: List<Track>, val playingIndex: Int? = null)

/**
 * What each edit of the play queue does to the list, as pure functions of the queue and where the playing song is: "only after the playing
 * index", "shuffle keeps the playing song first", "unshuffle restores the order and appends new songs". They lived inside [PlayQueue], each one
 * reading the live player index, so testing "move item 3 by +1 while shuffled" needed a fake player, timing and a launched hand-over. Here they
 * are tested on plain lists; [PlayQueue] reads the playing index, applies the result, and keeps the player in step (the hand-over, its own job).
 *
 * Each returns null when the edit would change nothing, so a caller neither rewrites the queue nor wakes the player for it. Like removal and
 * reordering, none of these is a skip: the playing song is never what they decide about.
 */
object QueueEdits {
    /** [tracks] after the queue. */
    fun append(queue: List<Track>, tracks: List<Track>): QueueEdit? = if (tracks.isEmpty()) null else QueueEdit(queue + tracks)

    /** Every other song, past and upcoming, gone; what is playing stays. */
    fun clear(queue: List<Track>, playing: Int): QueueEdit? {
        if (queue.isEmpty()) return null
        return QueueEdit(listOf(queue[playing.coerceIn(0, queue.lastIndex)]))
    }

    /** The upcoming song at [index]. Only a song after the playing one: removing the playing one would be deciding what plays next, a skip. */
    fun removeAt(queue: List<Track>, playing: Int, index: Int): QueueEdit? {
        if (index !in queue.indices || index <= playing) return null
        return QueueEdit(queue.toMutableList().also { it.removeAt(index) })
    }

    /** [serverId]'s upcoming songs, for a server that was removed and can no longer be fetched from. The playing song stays whichever server it is from. */
    fun removeServer(queue: List<Track>, playing: Int, serverId: String): QueueEdit? {
        if (queue.isEmpty()) return null
        val current = playing.coerceIn(0, queue.lastIndex)
        val trimmed = queue.filterIndexed { index, track -> index <= current || ServerScope.serverOf(track.id) != serverId }
        return if (trimmed.size == queue.size) null else QueueEdit(trimmed)
    }

    /** The upcoming song at [index] moved by [delta] slots. Both its place and its destination must be after the playing song. */
    fun move(queue: List<Track>, playing: Int, index: Int, delta: Int): QueueEdit? {
        val target = index + delta
        if (index !in queue.indices || target !in queue.indices) return null
        if (index <= playing || target <= playing) return null
        return QueueEdit(queue.toMutableList().also { it.add(target, it.removeAt(index)) })
    }

    /** What turning shuffle on does: the playing song first, everything else (already-played songs included) reshuffled behind it, and the order to remember so it can be undone. */
    class Shuffled(val edit: QueueEdit, val originalOrder: List<String>)

    fun shuffle(queue: List<Track>, playing: Int, random: Random = Random.Default): Shuffled? {
        if (queue.isEmpty()) return null
        val current = playing.coerceIn(0, queue.lastIndex)
        val rest = queue.filterIndexed { i, _ -> i != current }
        if (rest.isEmpty()) return null // one song: nothing to shuffle
        return Shuffled(QueueEdit(listOf(queue[current]) + rest.shuffled(random), playingIndex = 0), originalOrder = queue.map { it.id })
    }

    /** What turning shuffle off does: the order [shuffle] remembered, with a song added meanwhile appended and one removed meanwhile dropped; the playing song wherever it falls. */
    fun unshuffle(queue: List<Track>, playing: Int, originalOrder: List<String>): QueueEdit? {
        if (queue.isEmpty()) return null
        val current = queue[playing.coerceIn(0, queue.lastIndex)]
        if (queue.size == 1) return null
        val byId = queue.associateBy { it.id }
        val restored = originalOrder.mapNotNull { byId[it] } + queue.filter { it.id !in originalOrder }
        return QueueEdit(restored, playingIndex = restored.indexOfFirst { it.id == current.id }.coerceAtLeast(0))
    }
}
