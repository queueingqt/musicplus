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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private const val PLACEHOLDER_PREFIX = "pending:"

@Serializable private data class FavoritePayload(val favoriteType: String, val favorite: Boolean)
@Serializable private data class PlaylistCreatePayload(val name: String)
@Serializable private data class PlaylistRenamePayload(val name: String)
@Serializable private data class PlaylistAddTrackPayload(val songId: String)
@Serializable private data class PlaylistRemoveTrackPayload(val position: Int)
@Serializable private data class PlaylistReorderPayload(val songIds: List<String>)

private enum class MutationType { FAVORITE, PLAYLIST_CREATE, PLAYLIST_RENAME, PLAYLIST_DELETE, PLAYLIST_ADD_TRACK, PLAYLIST_REMOVE_TRACK, PLAYLIST_REORDER }

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
        pendingMutationDao.observePendingForTarget(MutationType.FAVORITE.name, id)

    suspend fun setArtistFavorite(id: String, favorite: Boolean) =
        favorite("artist", id, favorite) { libraryRepository.setArtistFavorite(id, favorite) }

    suspend fun setAlbumFavorite(id: String, favorite: Boolean) =
        favorite("album", id, favorite) { libraryRepository.setAlbumFavorite(id, favorite) }

    suspend fun setTrackFavorite(id: String, favorite: Boolean) =
        favorite("track", id, favorite) { libraryRepository.setTrackFavorite(id, favorite) }

    private suspend inline fun favorite(type: String, id: String, favorite: Boolean, attempt: () -> WriteOutcome) {
        if (attempt() == WriteOutcome.FAILED) {
            enqueue(MutationType.FAVORITE, id, FavoritePayload(type, favorite))
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
                enqueue(MutationType.PLAYLIST_CREATE, placeholderId, PlaylistCreatePayload(name))
                placeholderId
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        if (playlistRepository.renamePlaylist(playlistId, name) == WriteOutcome.FAILED) {
            enqueue(MutationType.PLAYLIST_RENAME, playlistId, PlaylistRenamePayload(name))
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        val outcome = playlistRepository.deletePlaylist(playlistId)
        // The playlist is locally gone either way (deletePlaylist always removes
        // the local row) — any other still-queued mutation against it is now
        // pointless to replay, whether or not the delete itself is queued below.
        pendingMutationDao.deleteForTarget(playlistId)
        if (outcome == WriteOutcome.FAILED) {
            enqueue(MutationType.PLAYLIST_DELETE, playlistId, payload = null)
        }
    }

    suspend fun addTrack(playlistId: String, songId: String) {
        if (playlistRepository.addTrack(playlistId, songId) == WriteOutcome.FAILED) {
            enqueue(MutationType.PLAYLIST_ADD_TRACK, playlistId, PlaylistAddTrackPayload(songId))
        }
    }

    suspend fun removeTrack(playlistId: String, position: Int) {
        if (playlistRepository.removeTrack(playlistId, position) == WriteOutcome.FAILED) {
            enqueue(MutationType.PLAYLIST_REMOVE_TRACK, playlistId, PlaylistRemoveTrackPayload(position))
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
            enqueue(MutationType.PLAYLIST_REORDER, playlistId, PlaylistReorderPayload(playlistRepository.currentSongIds(playlistId)))
        }
    }

    private suspend fun enqueue(type: MutationType, targetId: String, payload: Any?) {
        pendingMutationDao.insert(
            PendingMutationEntity(
                type = type.name,
                targetId = targetId,
                payloadJson = payload?.let { encode(type, it) } ?: "",
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun encode(type: MutationType, payload: Any): String = when (type) {
        MutationType.FAVORITE -> Json.encodeToString(payload as FavoritePayload)
        MutationType.PLAYLIST_CREATE -> Json.encodeToString(payload as PlaylistCreatePayload)
        MutationType.PLAYLIST_RENAME -> Json.encodeToString(payload as PlaylistRenamePayload)
        MutationType.PLAYLIST_ADD_TRACK -> Json.encodeToString(payload as PlaylistAddTrackPayload)
        MutationType.PLAYLIST_REMOVE_TRACK -> Json.encodeToString(payload as PlaylistRemoveTrackPayload)
        MutationType.PLAYLIST_REORDER -> Json.encodeToString(payload as PlaylistReorderPayload)
        MutationType.PLAYLIST_DELETE -> ""
    }

    /**
     * Replays every pending mutation in FIFO order, stopping at the first
     * failure (see class doc). Returns true if the queue fully drained.
     */
    suspend fun drainQueue(): Boolean {
        for (row in pendingMutationDao.getAllInOrder()) {
            val outcome = replay(row)
            if (outcome == WriteOutcome.FAILED) {
                pendingMutationDao.recordFailure(row.id, "attempt ${row.attemptCount + 1} failed")
                return false
            }
            pendingMutationDao.delete(row.id)
        }
        return true
    }

    private suspend fun replay(row: PendingMutationEntity): WriteOutcome = when (MutationType.valueOf(row.type)) {
        MutationType.FAVORITE -> {
            val payload = Json.decodeFromString<FavoritePayload>(row.payloadJson)
            when (payload.favoriteType) {
                "artist" -> libraryRepository.setArtistFavorite(row.targetId, payload.favorite)
                "album" -> libraryRepository.setAlbumFavorite(row.targetId, payload.favorite)
                else -> libraryRepository.setTrackFavorite(row.targetId, payload.favorite)
            }
        }
        MutationType.PLAYLIST_CREATE -> {
            val payload = Json.decodeFromString<PlaylistCreatePayload>(row.payloadJson)
            when (val result = playlistRepository.createPlaylist(payload.name)) {
                is CreatePlaylistResult.Created -> {
                    reassignPlaceholder(row.targetId, result.id)
                    WriteOutcome.SUCCESS
                }
                CreatePlaylistResult.NotConfigured, CreatePlaylistResult.Failed -> WriteOutcome.FAILED
            }
        }
        MutationType.PLAYLIST_RENAME -> {
            val payload = Json.decodeFromString<PlaylistRenamePayload>(row.payloadJson)
            playlistRepository.renamePlaylist(row.targetId, payload.name)
        }
        MutationType.PLAYLIST_DELETE -> playlistRepository.deletePlaylist(row.targetId)
        MutationType.PLAYLIST_ADD_TRACK -> {
            val payload = Json.decodeFromString<PlaylistAddTrackPayload>(row.payloadJson)
            playlistRepository.addTrack(row.targetId, payload.songId)
        }
        MutationType.PLAYLIST_REMOVE_TRACK -> {
            val payload = Json.decodeFromString<PlaylistRemoveTrackPayload>(row.payloadJson)
            playlistRepository.replayRemoveTrack(row.targetId, payload.position)
        }
        MutationType.PLAYLIST_REORDER -> {
            val payload = Json.decodeFromString<PlaylistReorderPayload>(row.payloadJson)
            playlistRepository.reorderTo(row.targetId, payload.songIds)
        }
    }

    /** A placeholder playlist finally has a real server id — move its row, its track rows, and every other queued mutation against it over in one go, so draining can continue against the real id right away. */
    private suspend fun reassignPlaceholder(placeholderId: String, realId: String) {
        playlistRepository.reassignPlaylistId(placeholderId, realId)
        pendingMutationDao.reassignTarget(placeholderId, realId)
    }
}

/**
 * The actual sync job — self-contained (builds its own DB/network clients rather
 * than reusing app-process singletons), same convention as DownloadRepository's
 * job, since WorkManager can run this in a fresh process with no warm state.
 * Runs on a periodic schedule (see AppGraph) as the background-app/process-killed
 * backstop, plus an opportunistic one-shot enqueue on every observed reconnect —
 * either way it lands here.
 */
@LightJob(SyncQueueRepository.JOB_KEY)
val syncPendingMutations: LightJobHandler = handler@{ lightContext, _ ->
    AppLogger.d("SyncQueueJob", "starting")
    val serverConfigRepository = ServerConfigRepository(lightContext.dataStore)
    // Nothing configured at all — not just "unreachable right now" — so there's
    // genuinely nowhere for a queued mutation to ever sync to.
    if (serverConfigRepository.serverConfig.first() == null) {
        AppLogger.d("SyncQueueJob", "no server configured, exiting")
        return@handler LightJobResult.Success()
    }

    val db = MusicPlusDatabase.create(lightContext)
    val count = db.pendingMutationDao().observeCount().first()
    AppLogger.d("SyncQueueJob", "pending count = $count")
    if (count == 0) return@handler LightJobResult.Success()

    val apiHolder = SubsonicApiHolder(serverConfigRepository)
    val connectivity = lightContext.connectivity
    val libraryRepository = LibraryRepository(apiHolder, db.artistDao(), db.albumDao(), db.trackDao(), connectivity)
    val playlistRepository = PlaylistRepository(apiHolder, db.playlistDao(), db.trackDao(), connectivity)
    val syncQueueRepository = SyncQueueRepository(db.pendingMutationDao(), libraryRepository, playlistRepository)

    if (syncQueueRepository.drainQueue()) LightJobResult.Success() else LightJobResult.Retry
}

/** Registers the periodic backstop schedule — see AppGraph.build(). The opportunistic reconnect-triggered one-shot (also wired there) is what makes this feel near-instant in the common case; this is just the floor for when the app isn't in foreground to observe that. */
fun scheduleSyncQueueJob(lightContext: SealedLightContext) {
    LightWork.enqueuePeriodic(lightContext, SyncQueueRepository.JOB_KEY, repeatInterval = 15.minutes)
}
