package com.musicplus.app.data

import com.musicplus.app.WriteOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a locally-made playlist's id starts with *after* its server scope, e.g. `<serverId>:pending:<uuid>` — see [ServerScope]. */
internal const val PLACEHOLDER_PREFIX = "pending:"

/** The type tag a queued favorite is stored under, so a query can find them without decoding JSON. */
private const val FAVORITE_TAG = "FAVORITE"

/** Favorites still waiting to be sent: a refresh must not overwrite them with the server's older answer (see [LocalStars]). */
suspend fun PendingMutationDao.pendingFavoriteIds(): Set<String> = getTargetIdsByType(FAVORITE_TAG).toHashSet()

/**
 * One thing the person did that has to reach a server: an intent, not a call. What it does to the phone's copy is [LocalFirstWrites]'s
 * `applyLocally`, what it says to the server is `send`, and both are exhaustive `when`s over this class, so a new kind cannot be added
 * without deciding both (and [typeTag]). Persisted, in a queue, until the server has it; the whole mutation is what is stored, so a row
 * decodes to the same intent it was made from.
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

    /** The full desired order, not a position delta: a replay must never re-run a swap against a copy that has already moved on. */
    @Serializable
    @SerialName("PLAYLIST_REORDER")
    data class PlaylistReorder(val songIds: List<String>) : PendingMutation()
}

/** A second exhaustive `when` rather than serialization's own discriminator, so a query like [LocalFirstWrites.isFavoritePending] can filter by type in the database without decoding JSON. */
private val PendingMutation.typeTag: String
    get() = when (this) {
        is PendingMutation.Favorite -> FAVORITE_TAG
        is PendingMutation.PlaylistCreate -> "PLAYLIST_CREATE"
        is PendingMutation.PlaylistRename -> "PLAYLIST_RENAME"
        PendingMutation.PlaylistDelete -> "PLAYLIST_DELETE"
        is PendingMutation.PlaylistAddTrack -> "PLAYLIST_ADD_TRACK"
        is PendingMutation.PlaylistRemoveTrack -> "PLAYLIST_REMOVE_TRACK"
        is PendingMutation.PlaylistReorder -> "PLAYLIST_REORDER"
    }

/**
 * The phone's own copy of the library, as far as a write is concerned: what each intent does to it. Room-backed in the app
 * ([RoomLocalCopy]); a test supplies an in-memory one. Every method is a plain local effect: nothing here talks to a server.
 */
interface LocalCopy {
    suspend fun setStarred(type: String, id: String, starred: Boolean)

    suspend fun playlistName(playlistId: String): String?
    suspend fun renamePlaylist(playlistId: String, name: String)

    /** Removes the playlist and its membership rows. */
    suspend fun deletePlaylist(playlistId: String)

    suspend fun songIds(playlistId: String): List<String>

    /** Replaces the playlist's membership and order. (A Phone Only playlist's count and length follow, since there is no server to tell it.) */
    suspend fun setSongIds(playlistId: String, songIds: List<String>)

    /** A local-only, empty row for a playlist made while the server may not be reachable, so it shows at once like a real one. */
    suspend fun adoptPlaylist(placeholderId: String, name: String)

    /** A placeholder playlist has its real id: its row and its membership rows move over together. */
    suspend fun reassignPlaylist(oldId: String, newId: String)
}

/** What a write came to: whether the server has it, and the id of what it was about (a created playlist's real id, once it has one). */
data class WriteResult(val outcome: WriteOutcome, val target: String)

/**
 * A write that reaches a server, the local-first way: it is applied to the phone's copy at once (so the screen shows it, offline or
 * not), sent to the server, and, when the send fails in a way worth retrying, queued so it is sent later. That whole shape is here, in
 * one call ([write]), and the retry is [drain]: callers state an intent and cannot forget the queueing, which used to be a step every
 * wrapper had to remember ("nothing forces this one; skipping it means a failed write is silently never retried").
 *
 * **A replay only sends.** The queue re-sends what the server missed; it never re-applies the change to the phone's copy, which the
 * original call already did. Replaying through the live call re-inserted a local row on every failed attempt of an offline
 * add-to-playlist (#69), and re-running a position-based edit against a copy that had since moved on would corrupt it.
 *
 * Replay is strict FIFO *per server* and stops at a server's first failure in a pass (a later edit of a playlist can depend on an
 * earlier one, most concretely on the create of a playlist made offline, and on a real offline window every later item for that server
 * fails the same way, so carrying on would only be a burst of guaranteed-failing calls). Each server has its own line, so one that
 * rejects an edit or is unreachable never holds up another's. A server that is off keeps its edits until it is switched on again.
 *
 * A playlist created offline gets a placeholder id (`<serverId>:pending:<uuid>`) at once, so it can be renamed, added to and so on
 * exactly like a real one; every edit against it queues under that id. When the create finally succeeds the placeholder is swapped for
 * the real id, in the phone's copy and in every still-queued edit, before the drain carries on.
 */
class LocalFirstWrites(
    private val queue: PendingMutationDao,
    private val local: LocalCopy,
    private val apis: ApiLookup,
    /** Only the edits of servers that are on are sent; the rest wait until their server is switched on again. */
    private val enabledServerIds: Flow<Set<String>>,
    /** False for a server known not to keep favorites: a heart on such a server's song stays on the phone and is never sent. */
    private val keepsFavorites: (serverId: String?) -> Boolean,
    /** After a send worked, reads [target] back from the server so the phone's copy matches its answer. */
    private val reconcile: suspend (target: String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val pendingCount: Flow<Int> = queue.observeCount()

    fun isFavoritePending(id: String): Flow<Boolean> = queue.observePendingForTarget(FAVORITE_TAG, id)

    /**
     * Applies [mutation] to the phone's copy, sends it, and queues it if the send failed. [target] is what it is about (an artist, album,
     * song or playlist id, scoped); for a playlist create it is the placeholder id, and the result says what it became.
     * Returns [WriteOutcome.NOT_CONFIGURED] with nothing queued when there is nothing to send to (a Phone Only playlist, a server that is
     * not set up or keeps no favorites) or nothing to do (a position that is not in the playlist).
     */
    suspend fun write(target: String, mutation: PendingMutation): WriteResult {
        if (!applyLocally(target, mutation)) return WriteResult(WriteOutcome.NOT_CONFIGURED, target)
        // The playlist is gone from the phone whatever the server says, so anything still queued against it is pointless to replay,
        // whether or not the delete itself ends up queued below.
        if (mutation == PendingMutation.PlaylistDelete) queue.deleteForTarget(target)
        val sent = send(target, mutation)
        if (sent.outcome == WriteOutcome.FAILED) enqueue(sent.target, mutation)
        return WriteResult(sent.outcome, sent.target)
    }

    /** Queues [mutation] for [target] without applying or sending it: for what the phone already holds and the server has yet to be told (the hearts made while a server could not keep favorites). */
    suspend fun enqueue(target: String, mutation: PendingMutation) {
        queue.insert(
            PendingMutationEntity(
                type = mutation.typeTag,
                targetId = target,
                payloadJson = Json.encodeToString(mutation),
                createdAtEpochMs = nowMs(),
            ),
        )
    }

    /** What [mutation] does to the phone's copy. False when it does nothing (and so is not worth sending or queueing). */
    private suspend fun applyLocally(target: String, mutation: PendingMutation): Boolean {
        when (mutation) {
            is PendingMutation.Favorite -> local.setStarred(mutation.favoriteType, target, mutation.favorite)
            is PendingMutation.PlaylistCreate -> local.adoptPlaylist(target, mutation.name)
            is PendingMutation.PlaylistRename -> local.renamePlaylist(target, mutation.name)
            PendingMutation.PlaylistDelete -> local.deletePlaylist(target)
            is PendingMutation.PlaylistAddTrack -> local.setSongIds(target, local.songIds(target) + mutation.songId)
            is PendingMutation.PlaylistRemoveTrack -> {
                val songIds = local.songIds(target)
                if (mutation.position !in songIds.indices) return false
                local.setSongIds(target, songIds.toMutableList().also { it.removeAt(mutation.position) })
            }
            is PendingMutation.PlaylistReorder -> local.setSongIds(target, mutation.songIds)
        }
        return true
    }

    private class Sent(val outcome: WriteOutcome, val target: String)

    /**
     * What [mutation] says to the server, and nothing else: this is also exactly what a replay does. Reads back the target afterwards
     * so the phone's copy matches the server's answer.
     */
    private suspend fun send(target: String, mutation: PendingMutation): Sent {
        val serverId = ServerScope.serverOf(target)
        if (serverId == null || serverId == ServerScope.PHONE) return Sent(WriteOutcome.NOT_CONFIGURED, target)
        if (mutation is PendingMutation.Favorite && !keepsFavorites(serverId)) return Sent(WriteOutcome.NOT_CONFIGURED, target)
        val api = apis.forId(target) ?: return Sent(WriteOutcome.NOT_CONFIGURED, target)
        return try {
            when (mutation) {
                is PendingMutation.Favorite -> {
                    if (mutation.favorite) api.star(target) else api.unstar(target)
                    Sent(WriteOutcome.SUCCESS, target)
                }
                is PendingMutation.PlaylistCreate -> {
                    val created = api.createPlaylist(mutation.name) ?: return Sent(WriteOutcome.FAILED, target)
                    swapPlaceholder(target, created.id)
                    reconcile(created.id)
                    Sent(WriteOutcome.SUCCESS, created.id)
                }
                is PendingMutation.PlaylistRename -> {
                    api.renamePlaylist(target, mutation.name)
                    reconcile(target)
                    Sent(WriteOutcome.SUCCESS, target)
                }
                PendingMutation.PlaylistDelete -> {
                    api.deletePlaylist(target)
                    Sent(WriteOutcome.SUCCESS, target)
                }
                is PendingMutation.PlaylistAddTrack -> {
                    if (!holdsOnly(target, listOf(mutation.songId))) return Sent(WriteOutcome.NOT_CONFIGURED, target)
                    api.addSongToPlaylist(target, mutation.songId)
                    reconcile(target)
                    Sent(WriteOutcome.SUCCESS, target)
                }
                is PendingMutation.PlaylistRemoveTrack -> {
                    api.removeSongFromPlaylist(target, mutation.position)
                    reconcile(target)
                    Sent(WriteOutcome.SUCCESS, target)
                }
                is PendingMutation.PlaylistReorder -> {
                    if (!holdsOnly(target, mutation.songIds)) return Sent(WriteOutcome.NOT_CONFIGURED, target)
                    val name = local.playlistName(target) ?: return Sent(WriteOutcome.FAILED, target)
                    api.reorderPlaylist(target, name, mutation.songIds)
                    reconcile(target)
                    Sent(WriteOutcome.SUCCESS, target)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "sending ${mutation.typeTag} for $target failed", e)
            Sent(WriteOutcome.FAILED, target)
        }
    }

    /** A playlist can only hold songs from its own server; anything else is kept on the phone and never sent. */
    private fun holdsOnly(playlistId: String, songIds: List<String>): Boolean {
        val owner = ServerScope.serverOf(playlistId)
        return songIds.all { ServerScope.serverOf(it) == owner }
    }

    /** A placeholder playlist finally has a real server id: move its row, its membership rows and every other queued edit over in one go, so draining carries on against the real id at once. */
    private suspend fun swapPlaceholder(placeholderId: String, realId: String) {
        local.reassignPlaylist(placeholderId, realId)
        queue.reassignTarget(placeholderId, realId)
    }

    /**
     * Replays every queued edit in FIFO order per server (see the class doc), sending only, stopping a server's line at its first failure.
     * Returns true if the queue fully drained. A row that cannot decode (an old-shaped one) is dropped rather than treated as a failure:
     * retrying can never fix a shape mismatch, and leaving it as its server's head would block every later edit forever.
     */
    suspend fun drain(): Boolean {
        val on = enabledServerIds.first()
        var drained = true
        val perServer = queue.getAllInOrder().groupBy { ServerScope.serverOf(it.targetId) }
        // The rows were read once, above. A create that succeeds retargets the rest of the queue in the database, but not the rows this
        // pass already holds, so the ids that changed are remembered and applied to the rows still to come: without it the edits made
        // against a placeholder were sent to it, failed, and only went through on the next pass.
        val renamed = HashMap<String, String>()
        for ((server, rows) in perServer) {
            if (server == null || server == ServerScope.PHONE) {
                // An edit that names no server, or only the phone, can never be sent anywhere.
                rows.forEach { queue.delete(it.id) }
                continue
            }
            if (server !in on) continue // waits until that server is switched on again
            for (row in rows) {
                val mutation = try {
                    Json.decodeFromString<PendingMutation>(row.payloadJson)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "row ${row.id} (type=${row.type}) undecodable, dropping", e)
                    queue.delete(row.id)
                    continue
                }
                val target = renamed[row.targetId] ?: row.targetId
                val sent = send(target, mutation)
                if (sent.target != target) renamed[row.targetId] = sent.target
                if (sent.outcome == WriteOutcome.FAILED) {
                    queue.recordFailure(row.id, "attempt ${row.attemptCount + 1} failed")
                    drained = false
                    break // this server's line stops here; the other servers' lines carry on
                }
                queue.delete(row.id)
            }
        }
        return drained
    }

    private companion object {
        const val TAG = "LocalFirstWrites"
    }
}
