package com.musicplus.app.data

import com.musicplus.app.WriteOutcome
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

internal const val PLACEHOLDER_PREFIX = "pending:"

/**
 * Adding a new mutation type now touches 2 spots, both compiler-enforced —
 * down from 5 (2 enforced) before this session's own /grilling pass turned
 * a `MutationType` enum + separate per-type `@Serializable` payload classes
 * + [SyncQueueRepository]'s own hand-written `encode()` dispatch `when`
 * into this single sealed hierarchy with polymorphic kotlinx.serialization
 * (automatic for a `@Serializable sealed class` whose `@Serializable`
 * subclasses live in the same compilation unit — no `SerializersModule`
 * wiring needed). The old `encode()` function's whole per-type `when` is
 * gone entirely: `Json.encodeToString(mutation)` already knows how to emit
 * the right shape — including a `"type"` discriminator — for whichever
 * subclass it's handed, polymorphically, with zero per-type code here.
 * 1. Add the subclass below.
 * 2. Add its branch to [SyncQueueRepository.replay]'s exhaustive `when`
 *    (compiler-enforced) and to [typeTag] just below it (also
 *    compiler-enforced — a second exhaustive `when`, kept deliberately
 *    separate from serialization's own discriminator so a DB-column query
 *    like [SyncQueueRepository.isFavoritePending] doesn't have to decode
 *    JSON just to filter by type).
 * 3. Actually call `enqueue(...)` from the wrapper method that attempts the
 *    write live and queues it on [WriteOutcome.FAILED] — nothing forces
 *    this one; skipping it means a failed write is silently never retried.
 *
 * [PendingMutationEntity.payloadJson] now holds the *whole* polymorphically
 * serialized mutation (its own embedded discriminator included), not just
 * the bare per-type fields the old payload classes held — a genuine
 * on-disk shape change, which is exactly why this stayed a checklist
 * instead of a remodel until it was actually grilled with the person,
 * 2026-09-18. Old-shaped rows (written before this change) can't decode
 * against the new shape — [SyncQueueRepository.drainQueue] treats that as
 * unfixable-by-retry and drops the row rather than blocking the FIFO queue
 * on it forever; accepted as low-risk given this table is normally
 * near-empty with no cross-install durability requirement.
 */
@Serializable
sealed class PendingMutation {
    @Serializable
    @SerialName("FAVORITE")
    data class Favorite(val favoriteType: String, val favorite: Boolean) : PendingMutation()

    @Serializable
    @SerialName("PLAYLIST_CREATE")
    data class PlaylistCreate(val name: String) : PendingMutation()

    @Serializable
    @SerialName("PLAYLIST_RENAME")
    data class PlaylistRename(val name: String) : PendingMutation()

    @Serializable
    @SerialName("PLAYLIST_DELETE")
    data object PlaylistDelete : PendingMutation()

    @Serializable
    @SerialName("PLAYLIST_ADD_TRACK")
    data class PlaylistAddTrack(val songId: String) : PendingMutation()

    @Serializable
    @SerialName("PLAYLIST_REMOVE_TRACK")
    data class PlaylistRemoveTrack(val position: Int) : PendingMutation()

    @Serializable
    @SerialName("PLAYLIST_REORDER")
    data class PlaylistReorder(val songIds: List<String>) : PendingMutation()
}

/** See [PendingMutation]'s own doc for why this exists as a second exhaustive `when` rather than reading serialization's own discriminator. */
private val PendingMutation.typeTag: String
    get() = when (this) {
        is PendingMutation.Favorite -> "FAVORITE"
        is PendingMutation.PlaylistCreate -> "PLAYLIST_CREATE"
        is PendingMutation.PlaylistRename -> "PLAYLIST_RENAME"
        PendingMutation.PlaylistDelete -> "PLAYLIST_DELETE"
        is PendingMutation.PlaylistAddTrack -> "PLAYLIST_ADD_TRACK"
        is PendingMutation.PlaylistRemoveTrack -> "PLAYLIST_REMOVE_TRACK"
        is PendingMutation.PlaylistReorder -> "PLAYLIST_REORDER"
    }

/**
 * Offline sync queue for the server writes [LibraryRepository]/[PlaylistRepository]
 * already attempt optimistically (issue #24): every write below tries live first,
 * and only reaches Room if that live attempt genuinely fails (not for
 * [WriteOutcome.NOT_CONFIGURED] — there's no server to eventually reach at all in
 * that case). Screens should call these wrapper methods instead of the underlying
 * repositories directly for anything write-shaped, so a failure is never silently
 * dropped on the floor again.
 *
 * Replay is strict FIFO and stops at the first failure in a given pass (rather
 * than skipping ahead) — a later mutation against the same playlist can depend on
 * an earlier one (most concretely: anything targeting a playlist that was itself
 * just created offline, see PLAYLIST_CREATE below), and on a real offline window
 * every subsequent item is going to fail identically anyway, so continuing would
 * just be a burst of guaranteed-failing network calls. [LightWork]'s own
 * [LightJobResult.Retry] backoff governs how soon the next pass runs.
 *
 * PLAYLIST_CREATE is the one genuinely special case: a playlist created while
 * offline gets a local-only placeholder id (`"pending:<uuid>"`) immediately, so
 * the person can rename/add tracks/etc to it right away exactly like a real one —
 * every other mutation type below just attempts live against that placeholder id,
 * fails naturally (it doesn't exist server-side yet), and queues through the exact
 * same generic path as any other offline failure. Once the real create replays
 * successfully, [reassignPlaceholder] retargets the playlist row, its track rows,
 * and every other still-queued mutation from the placeholder id to the real one in
 * a single pass, before draining continues.
 */
class SyncQueueRepository(
    private val pendingMutationDao: PendingMutationDao,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
) {
    companion object {
        const val JOB_KEY = "sync-pending-mutations"
    }

    val pendingCount: Flow<Int> = pendingMutationDao.observeCount()

    fun isFavoritePending(id: String): Flow<Boolean> =
        pendingMutationDao.observePendingForTarget("FAVORITE", id)

    suspend fun setArtistFavorite(id: String, favorite: Boolean) =
        favorite("artist", id, favorite) { libraryRepository.setArtistFavorite(id, favorite) }

    suspend fun setAlbumFavorite(id: String, favorite: Boolean) =
        favorite("album", id, favorite) { libraryRepository.setAlbumFavorite(id, favorite) }

    suspend fun setTrackFavorite(id: String, favorite: Boolean) =
        favorite("track", id, favorite) { libraryRepository.setTrackFavorite(id, favorite) }

    private suspend inline fun favorite(type: String, id: String, favorite: Boolean, attempt: () -> WriteOutcome) {
        if (attempt() == WriteOutcome.FAILED) {
            enqueue(id, PendingMutation.Favorite(type, favorite))
        }
    }

    /** Returns the playlist's id — real if the create succeeded (or a placeholder if it's now queued) — or null only when no server is configured at all. */
    suspend fun createPlaylist(name: String): String? {
        return when (val result = playlistRepository.createPlaylist(name)) {
            is CreatePlaylistResult.Created -> result.id
            CreatePlaylistResult.NotConfigured -> null
            CreatePlaylistResult.Failed -> {
                val placeholderId = PLACEHOLDER_PREFIX + UUID.randomUUID()
                playlistRepository.adoptLocalPlaylist(placeholderId, name)
                enqueue(placeholderId, PendingMutation.PlaylistCreate(name))
                placeholderId
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        if (playlistRepository.renamePlaylist(playlistId, name) == WriteOutcome.FAILED) {
            enqueue(playlistId, PendingMutation.PlaylistRename(name))
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        val outcome = playlistRepository.deletePlaylist(playlistId)
        // The playlist is locally gone either way (deletePlaylist always removes
        // the local row) — any other still-queued mutation against it is now
        // pointless to replay, whether or not the delete itself is queued below.
        pendingMutationDao.deleteForTarget(playlistId)
        if (outcome == WriteOutcome.FAILED) {
            enqueue(playlistId, PendingMutation.PlaylistDelete)
        }
    }

    suspend fun addTrack(playlistId: String, songId: String) {
        if (playlistRepository.addTrack(playlistId, songId) == WriteOutcome.FAILED) {
            enqueue(playlistId, PendingMutation.PlaylistAddTrack(songId))
        }
    }

    suspend fun removeTrack(playlistId: String, position: Int) {
        if (playlistRepository.removeTrack(playlistId, position) == WriteOutcome.FAILED) {
            enqueue(playlistId, PendingMutation.PlaylistRemoveTrack(position))
        }
    }

    suspend fun moveTrackUp(playlistId: String, position: Int) = move(playlistId) { playlistRepository.moveTrackUp(playlistId, position) }
    suspend fun moveTrackDown(playlistId: String, position: Int) = move(playlistId) { playlistRepository.moveTrackDown(playlistId, position) }

    /**
     * [attempt] (moveTrackUp/moveTrackDown) already applies the swap to the
     * local cache before returning, win or lose — so on failure, the *result*
     * of that swap (read back out via [PlaylistRepository.currentSongIds]) is
     * exactly what needs to be queued, not the position delta: replay uses
     * [PlaylistRepository.reorderTo] (a full-list replace), never re-runs the
     * position-based swap itself, since a second swap against the
     * already-swapped local cache would corrupt it — see reorderTo's own doc.
     */
    private suspend inline fun move(playlistId: String, attempt: () -> WriteOutcome) {
        if (attempt() == WriteOutcome.FAILED) {
            enqueue(playlistId, PendingMutation.PlaylistReorder(playlistRepository.currentSongIds(playlistId)))
        }
    }

    private suspend fun enqueue(targetId: String, mutation: PendingMutation) {
        pendingMutationDao.insert(
            PendingMutationEntity(
                type = mutation.typeTag,
                targetId = targetId,
                payloadJson = Json.encodeToString(mutation),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Replays every pending mutation in FIFO order, stopping at the first
     * genuine failure (see class doc). Returns true if the queue fully
     * drained. A row whose [PendingMutationEntity.payloadJson] can't even
     * decode (see [PendingMutation]'s own doc — an old-shaped row from
     * before this session's sealed-class remodel) is dropped instead of
     * treated as a failure: retrying can never fix a shape mismatch, and
     * leaving it as the queue's own FIFO head would otherwise block every
     * subsequent mutation forever.
     */
    suspend fun drainQueue(): Boolean {
        for (row in pendingMutationDao.getAllInOrder()) {
            val outcome = try {
                replay(row)
            } catch (e: Exception) {
                AppLogger.e("SyncQueueRepository", "row ${row.id} (type=${row.type}) undecodable, dropping", e)
                pendingMutationDao.delete(row.id)
                continue
            }
            if (outcome == WriteOutcome.FAILED) {
                pendingMutationDao.recordFailure(row.id, "attempt ${row.attemptCount + 1} failed")
                return false
            }
            pendingMutationDao.delete(row.id)
        }
        return true
    }

    private suspend fun replay(row: PendingMutationEntity): WriteOutcome =
        when (val mutation = Json.decodeFromString<PendingMutation>(row.payloadJson)) {
            is PendingMutation.Favorite -> when (mutation.favoriteType) {
                "artist" -> libraryRepository.setArtistFavorite(row.targetId, mutation.favorite)
                "album" -> libraryRepository.setAlbumFavorite(row.targetId, mutation.favorite)
                else -> libraryRepository.setTrackFavorite(row.targetId, mutation.favorite)
            }
            is PendingMutation.PlaylistCreate -> when (val result = playlistRepository.createPlaylist(mutation.name)) {
                is CreatePlaylistResult.Created -> {
                    reassignPlaceholder(row.targetId, result.id)
                    WriteOutcome.SUCCESS
                }
                CreatePlaylistResult.NotConfigured, CreatePlaylistResult.Failed -> WriteOutcome.FAILED
            }
            is PendingMutation.PlaylistRename -> playlistRepository.renamePlaylist(row.targetId, mutation.name)
            PendingMutation.PlaylistDelete -> playlistRepository.deletePlaylist(row.targetId)
            is PendingMutation.PlaylistAddTrack -> playlistRepository.addTrack(row.targetId, mutation.songId)
            is PendingMutation.PlaylistRemoveTrack -> playlistRepository.replayRemoveTrack(row.targetId, mutation.position)
            is PendingMutation.PlaylistReorder -> playlistRepository.reorderTo(row.targetId, mutation.songIds)
        }

    /** A placeholder playlist finally has a real server id — move its row, its track rows, and every other queued mutation against it over in one go, so draining can continue against the real id right away. */
    private suspend fun reassignPlaceholder(placeholderId: String, realId: String) {
        playlistRepository.reassignPlaylistId(placeholderId, realId)
        pendingMutationDao.reassignTarget(placeholderId, realId)
    }
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
