package com.musicplus.app.data.playback

import com.musicplus.app.RepeatMode
import com.musicplus.app.Track
import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.PlaybackStateRepository
import com.musicplus.app.data.QueueDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What was saved of the last session, made sound: the queue, where in it, and the modes. */
data class RestoredPlayback(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val albumArtUrl: String?,
)

/**
 * Keeps the queue and the scalar playback state (position, index, shuffle, repeat, art) across an app restart (issue #27), so the
 * queue survives instead of starting empty every time.
 *
 * Restoring is deliberately not a player operation: it only reads. Nothing touches the player and nothing is fetched, so every
 * screen's title, art and mode indicators can show the restored state at once without a cold-start network fetch nobody asked for;
 * the player loads it when the person presses play. Any saved song id no longer in the local library (app data cleared, or never
 * fetched) is silently dropped rather than failing the whole restore.
 *
 * Two orderings are the reason this is a module. [restore] must finish before [keepQueueSaved] starts, or the writer's first
 * emission would be the repository's own empty initial queue racing ahead of the read and overwriting the very rows about to be
 * read. And restore must lose to a real play: the first Room query of a process (a cold SQLite open plus the migration checks) can
 * take longer than resolving an already-cached track, and whichever finished last used to win, which was reliably the stale restore
 * overwriting the song the person had just tapped (reproduced 2026-09-18). [restore] therefore asks [stillWanted] before it starts
 * and again right before it returns.
 */
class PlaybackPersistence(
    private val queueDao: QueueDao,
    private val stateRepository: PlaybackStateRepository,
    private val tracksByIds: suspend (List<String>) -> List<Track>,
) {
    /** The last session, or null when there is nothing usable or [stillWanted] turned false while reading. */
    suspend fun restore(stillWanted: () -> Boolean): RestoredPlayback? {
        if (!stillWanted()) return null
        val songIds = queueDao.observeQueue().first().map { it.songId }
        if (songIds.isEmpty()) return null
        val byId = tracksByIds(songIds).associateBy { it.id }
        val saved = stateRepository.read()
        if (!stillWanted()) return null
        val restored = restoredFrom(songIds, byId, saved) ?: return null
        AppLogger.d("PlaybackPersistence", "restore(): ${restored.tracks.size}/${songIds.size} track(s), index=${restored.index}, positionMs=${restored.positionMs}")
        return restored
    }

    /** Writes the queue every time it changes. Call only once [restore] has finished. */
    suspend fun keepQueueSaved(queue: Flow<List<Track>>) {
        queue.collect { tracks -> queueDao.replaceQueue(tracks.map { it.id }) }
    }

    suspend fun save(saved: PlaybackStateRepository.Saved) = stateRepository.save(saved)

    /**
     * A coarse periodic save, not a `state` collector: position updates every 250 ms and a DataStore write is real disk I/O, while
     * resuming only needs "close enough". [snapshot] is what would be saved right now, or null when nothing is worth saving yet.
     */
    fun savePeriodically(scope: CoroutineScope, intervalMs: Long = SAVE_INTERVAL_MS, snapshot: () -> PlaybackStateRepository.Saved?): Job =
        scope.launch {
            while (true) {
                delay(intervalMs)
                try {
                    snapshot()?.let { save(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("PlaybackPersistence", "periodic save failed", e)
                }
            }
        }

    companion object {
        /** How often the resume position is checkpointed to disk during ordinary playback. */
        const val SAVE_INTERVAL_MS = 5_000L

        /**
         * The saved [songIds] in their saved order, restricted to those still in [byId], with the saved state made sound: the index
         * kept inside the queue, and shuffle winning over repeat-one (a combination saved before the two became mutually exclusive
         * must not come back, same as if they were toggled in that order live). Null when no saved song is left.
         */
        fun restoredFrom(songIds: List<String>, byId: Map<String, Track>, saved: PlaybackStateRepository.Saved): RestoredPlayback? {
            val tracks = songIds.mapNotNull { byId[it] }
            if (tracks.isEmpty()) return null
            return RestoredPlayback(
                tracks = tracks,
                index = saved.currentIndex.coerceIn(0, tracks.lastIndex),
                positionMs = saved.positionMs,
                shuffle = saved.shuffle,
                repeatMode = if (saved.shuffle && saved.repeatMode == RepeatMode.REPEAT_TRACK) RepeatMode.OFF else saved.repeatMode,
                albumArtUrl = saved.albumArtUrl,
            )
        }
    }
}
