package com.musicplus.app.data

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * What screens call for anything write-shaped (issue #24): each function states an intent and hands it to [LocalFirstWrites], which
 * applies it to the phone's copy, sends it, and queues it if the send failed (and replays the queue: see [drainQueue]). Everything that
 * makes a write local-first, offline-safe and replayable lives there; this is only the vocabulary of what a person can do, so a screen
 * cannot forget the queueing (it is not a step any of these has to remember).
 */
class SyncQueueRepository(
    private val writes: LocalFirstWrites,
    private val local: LocalCopy,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val apis: ApiLookup,
) {
    companion object {
        const val JOB_KEY = "sync-pending-mutations"
    }

    val pendingCount: Flow<Int> = writes.pendingCount

    fun isFavoritePending(id: String): Flow<Boolean> = writes.isFavoritePending(id)

    suspend fun setArtistFavorite(id: String, favorite: Boolean) { writes.write(id, PendingMutation.Favorite("artist", favorite)) }

    suspend fun setAlbumFavorite(id: String, favorite: Boolean) { writes.write(id, PendingMutation.Favorite("album", favorite)) }

    suspend fun setTrackFavorite(id: String, favorite: Boolean) { writes.write(id, PendingMutation.Favorite("track", favorite)) }

    /**
     * Makes a playlist at [home]: a server id, or [ServerScope.PHONE] for one that lives only on the phone (nothing is sent or queued
     * for that). Returns the playlist's id, real if the create reached the server or a placeholder if it is now queued, or null when
     * that server is not set up.
     */
    suspend fun createPlaylist(name: String, home: String): String? {
        if (home == ServerScope.PHONE) return playlistRepository.createPhonePlaylist(name)
        if (apis.forServer(home) == null) return null
        val placeholderId = ServerScope.scope(home, PLACEHOLDER_PREFIX + UUID.randomUUID())
        return writes.write(placeholderId, PendingMutation.PlaylistCreate(name)).target
    }

    /** A new Phone Only copy of [playlistId] with [songId] added; the server's playlist stays exactly as it was. Returns the copy's id, or null if the original could not be read in full. */
    suspend fun addToPhoneCopy(playlistId: String, songId: String): String? =
        playlistRepository.copyToPhone(playlistId, songId)

    /**
     * A server that did not keep favorites now does: the hearts made on the phone in the meantime are sent to it. A heart already
     * there is simply set again. Queued directly: the phone already holds them.
     */
    suspend fun sendPhoneOnlyFavorites(serverId: String) {
        for ((type, id) in libraryRepository.localFavorites(serverId)) {
            writes.enqueue(id, PendingMutation.Favorite(type, true))
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) { writes.write(playlistId, PendingMutation.PlaylistRename(name)) }

    suspend fun deletePlaylist(playlistId: String) { writes.write(playlistId, PendingMutation.PlaylistDelete) }

    suspend fun addTrack(playlistId: String, songId: String) { writes.write(playlistId, PendingMutation.PlaylistAddTrack(songId)) }

    suspend fun removeTrack(playlistId: String, position: Int) { writes.write(playlistId, PendingMutation.PlaylistRemoveTrack(position)) }

    suspend fun moveTrackUp(playlistId: String, position: Int) = move(playlistId, position, position - 1)
    suspend fun moveTrackDown(playlistId: String, position: Int) = move(playlistId, position, position + 1)

    /**
     * There is no native "move" on either backend, so a reorder rewrites the whole order. The new order is worked out from the phone's
     * copy here, and it is the whole order that is written and queued, never the position delta: a replay must not re-run a swap
     * against a copy that has since moved on.
     */
    private suspend fun move(playlistId: String, from: Int, to: Int) {
        val songIds = local.songIds(playlistId)
        if (from !in songIds.indices || to !in songIds.indices) return
        val moved = songIds.toMutableList().also { it.add(to, it.removeAt(from)) }
        writes.write(playlistId, PendingMutation.PlaylistReorder(moved))
    }

    /** Replays what the server missed: see [LocalFirstWrites.drain]. Returns true if the queue fully drained. */
    suspend fun drainQueue(): Boolean = writes.drain()
}

/**
 * The actual sync job. Runs on a periodic schedule (see AppGraph) as the
 * background-app/process-killed backstop, plus an opportunistic one-shot
 * enqueue on every observed reconnect — either way it lands here.
 *
 * Goes through `AppGraph.from(lightContext)` rather than building its own
 * `MusicPlusDatabase`/repositories from scratch — same fix, same reason as
 * `DownloadRepository`'s job (see its doc): a second `Room` instance against
 * the same file doesn't notify the app's own live Flows when this job writes
 * through it, so a synced favorite/playlist edit's "still syncing" indicator
 * only ever cleared after the screen was reopened, never live. `AppGraph.from`
 * is a memoized per-process singleton, so this transparently reuses the app's
 * already-open instance when the process is alive to observe it, and builds
 * a fresh one exactly as before on a cold-process WorkManager run.
 */
@LightJob(SyncQueueRepository.JOB_KEY)
val syncPendingMutations: LightJobHandler = handler@{ lightContext, _ ->
    AppLogger.d("SyncQueueJob", "starting")
    val graph = AppGraph.from(lightContext)
    // Nothing configured at all — not just "unreachable right now" — so there's
    // genuinely nowhere for a queued mutation to ever sync to.
    if (graph.serverConfigRepository.serverConfig.first() == null) {
        AppLogger.d("SyncQueueJob", "no server configured, exiting")
        return@handler LightJobResult.Success()
    }

    val count = graph.database.pendingMutationDao().observeCount().first()
    AppLogger.d("SyncQueueJob", "pending count = $count")
    if (count == 0) return@handler LightJobResult.Success()

    if (graph.syncQueueRepository.drainQueue()) LightJobResult.Success() else LightJobResult.Retry
}

/** Registers the periodic backstop schedule — see AppGraph.build(). The opportunistic reconnect-triggered one-shot (also wired there) is what makes this feel near-instant in the common case; this is just the floor for when the app isn't in foreground to observe that. */
fun scheduleSyncQueueJob(lightContext: SealedLightContext) {
    LightWork.enqueuePeriodic(lightContext, SyncQueueRepository.JOB_KEY, repeatInterval = 15.minutes)
}
