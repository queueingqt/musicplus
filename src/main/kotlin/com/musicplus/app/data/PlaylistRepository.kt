@file:OptIn(ExperimentalCoroutinesApi::class)

package com.musicplus.app.data

import com.musicplus.app.Playlist
import com.musicplus.app.Track
import com.musicplus.app.WriteOutcome
import com.musicplus.app.data.ServerLabels.labelledBy
import com.thelightphone.sdk.LightConnectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Cache-then-network access to server playlists, mirroring [LibraryRepository]'s
 * observe/refresh pattern. Split into its own class (rather than folded into
 * LibraryRepository) because membership/reorder is genuinely more than one more
 * `observeX`/`refreshX` pair — creating, renaming, deleting, and especially
 * reordering (no native Subsonic "move" endpoint — see [SubsonicApi.reorderPlaylist])
 * all need to read the current server-round-tripped track order back out, not just
 * upsert a flat list.
 *
 * Track.downloadStatus/localFilePath are joined against [downloadRepository] at
 * read time — see [LibraryRepository]'s own class doc for why (same fix, same
 * reason, 2026-09-18 architecture review + this session's own /grilling pass).
 */
class PlaylistRepository(
    private val apiHolder: SubsonicApiHolder,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val connectivity: LightConnectivity,
    private val downloadRepository: DownloadRepository,
    /** See [LibraryRepository]'s parameter of the same name. */
    private val shownServerIds: Flow<List<String>>,
    private val serverSyncStatus: ServerSyncStatus,
) {
    fun observePlaylists(): Flow<List<Playlist>> =
        shownServerIds.flatMapLatest { playlistDao.observeAll(it).retryOnTransientDbError() }.map { it.map { entity -> entity.toDomain() } }.labelledBy(ServerLabels::playlists)

    /** Read-only passthrough for [SyncQueueRepository], which needs the just-applied local order to build a PLAYLIST_REORDER payload without reaching into the DAO layer directly. */
    suspend fun currentSongIds(playlistId: String): List<String> = playlistDao.getSongIdsInOrder(playlistId)

    /**
     * Local-only row for a playlist [SyncQueueRepository] just created offline, so it shows up immediately (empty)
     * exactly like a real one, before the create has actually synced. Returns its placeholder id (scoped to the
     * active server, which is where the create will be replayed), or null when no server is configured.
     */
    suspend fun adoptLocalPlaylist(name: String): String? {
        val api = apiHolder.get() ?: return null
        val placeholderId = ServerScope.scope(api.serverId, PLACEHOLDER_PREFIX + UUID.randomUUID())
        playlistDao.upsert(PlaylistEntity(placeholderId, name, songCount = 0, durationSec = 0))
        return placeholderId
    }

    /** A playlist can only hold songs from its own server; anything else is kept on the phone and never sent. */
    private fun holdsOnly(playlistId: String, songIds: List<String>): Boolean {
        val owner = ServerScope.serverOf(playlistId)
        return songIds.all { ServerScope.serverOf(it) == owner }
    }

    /** Passthrough for [SyncQueueRepository]'s PLAYLIST_CREATE replay — see [PlaylistDao.reassignPlaylistId]. */
    suspend fun reassignPlaylistId(oldId: String, newId: String) {
        playlistDao.reassignPlaylistId(oldId, newId)
    }

    fun observePlaylist(playlistId: String): Flow<Playlist?> =
        playlistDao.observeById(playlistId).map { it?.toDomain() }

    // includeCoverArt = false — PlaylistDetailScreen's rows show no art;
    // leading is reorder icons only when reorderMode is on. See
    // TrackMapping.kt's toTrack doc.
    fun observeTracks(playlistId: String): Flow<List<Track>> =
        combine(playlistDao.observeTracks(playlistId).retryOnTransientDbError(), downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(apiHolder, byId[it.id], includeCoverArt = false) }
        }

    /**
     * No-op (leaves the cache as-is) when offline, not yet configured, or the
     * network call itself fails — same convention (and same shared shape) as
     * [LibraryRepository.refresh]; see its doc for why the connectivity check
     * alone isn't sufficient.
     */
    private suspend fun refresh(label: String, ownerId: String? = null, action: suspend (SubsonicApi) -> Unit) {
        if (!connectivity.currentStatus.isConnected) return
        if (ownerId != null) {
            apiHolder.forId(ownerId)?.let { runRefresh(label, it, action) }
            return
        }
        // Every server that is on, each on its own — see LibraryRepository.refresh.
        val servers = shownServerIds.first()
        coroutineScope {
            for (id in servers) launch { apiHolder.forServer(id)?.let { runRefresh(label, it, action) } }
        }
    }

    private suspend fun runRefresh(label: String, api: SubsonicApi, action: suspend (SubsonicApi) -> Unit) {
        try {
            action(api)
            serverSyncStatus.refreshed(api.serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cache left as-is deliberately — see [refresh]'s own doc above.
            AppLogger.e("PlaylistRepository", "$label failed (server ${api.serverId})", e)
        }
    }

    private fun live(api: SubsonicApi) = AppServerPrefs.servers.value.value.any { it.id == api.serverId }

    /**
     * Additions, renames and deletions on the server — see [mirrorFromServer].
     * A playlist created offline (a `pending:` placeholder id) isn't on the
     * server yet, so it is never mistaken for one the server deleted.
     */
    suspend fun refreshPlaylists() = refresh("refreshPlaylists") { api ->
        mirrorFromServer(
            label = "playlists",
            idOf = PlaylistEntity::id,
            cached = { playlistDao.getAllFor(api.serverId).associateBy { it.id } },
            fetchAll = { onPage -> onPage(api.getPlaylists().map { it.toEntity() }) },
            write = { if (live(api)) playlistDao.upsertAll(it) },
            remove = { if (live(api)) playlistDao.deleteWithTracks(it) },
            keep = { candidates -> candidates.filterTo(HashSet()) { ServerScope.nativeOf(it).startsWith(PLACEHOLDER_PREFIX) } },
        )
    }

    suspend fun refreshPlaylistDetail(playlistId: String) = refresh("refreshPlaylistDetail($playlistId)", ownerId = playlistId) { api ->
        val detail = api.getPlaylist(playlistId) ?: return@refresh
        playlistDao.upsert(detail.toEntity())
        trackDao.upsertAll(detail.entry.map { it.toTrackEntity() })
        playlistDao.replaceTracks(
            playlistId,
            detail.entry.mapIndexed { index, song -> PlaylistTrackEntity(playlistId, index, song.id) },
        )
    }

    /** Returns the new playlist's server id on success — see [SyncQueueRepository] for the offline case, which this alone doesn't handle. */
    suspend fun createPlaylist(name: String, onServerOf: String? = null): CreatePlaylistResult {
        // A new playlist goes on the active server; a replayed offline create goes on the server its placeholder was made for.
        val api = (if (onServerOf != null) apiHolder.forId(onServerOf) else apiHolder.get()) ?: return CreatePlaylistResult.NotConfigured
        return try {
            val created = api.createPlaylist(name) ?: return CreatePlaylistResult.Failed
            playlistDao.upsert(created.toEntity())
            CreatePlaylistResult.Created(created.id)
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "createPlaylist(\"$name\") failed", e)
            CreatePlaylistResult.Failed
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String): WriteOutcome {
        val api = apiHolder.forId(playlistId) ?: return WriteOutcome.NOT_CONFIGURED
        // Optimistic local rename so the title updates immediately even if the
        // network call below is slow/offline/fails; refreshPlaylistDetail
        // reconciles it on the next successful refresh either way.
        playlistDao.getById(playlistId)?.let { playlistDao.upsert(it.copy(name = name)) }
        return try {
            api.renamePlaylist(playlistId, name)
            refreshPlaylistDetail(playlistId)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "renamePlaylist($playlistId, \"$name\") failed", e)
            WriteOutcome.FAILED
        }
    }

    /**
     * Always removes the local copy regardless of server outcome — matching
     * this function's own pre-existing local-delete intent — but still reports
     * [WriteOutcome.FAILED] so a failed server-side delete gets queued and
     * retried later rather than silently leaving the playlist alive server-side
     * forever.
     */
    suspend fun deletePlaylist(playlistId: String): WriteOutcome {
        val api = apiHolder.forId(playlistId)
        val outcome = try {
            if (api != null) {
                api.deletePlaylist(playlistId)
                WriteOutcome.SUCCESS
            } else {
                WriteOutcome.NOT_CONFIGURED
            }
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "deletePlaylist($playlistId) server call failed", e)
            WriteOutcome.FAILED
        }
        playlistDao.delete(playlistId)
        return outcome
    }

    /**
     * Appends locally first (same optimistic convention as the other writes
     * below) — without it, adding a track to a playlist that's still offline
     * (e.g. one just created while offline — see [SyncQueueRepository]'s
     * PLAYLIST_CREATE handling) would show zero tracks until it eventually
     * syncs, defeating the point of offline playlist creation.
     */
    suspend fun addTrack(playlistId: String, songId: String): WriteOutcome {
        val nextPosition = playlistDao.getSongIdsInOrder(playlistId).size
        playlistDao.insertTracks(listOf(PlaylistTrackEntity(playlistId, nextPosition, songId)))
        if (!holdsOnly(playlistId, listOf(songId))) return WriteOutcome.NOT_CONFIGURED
        val api = apiHolder.forId(playlistId) ?: return WriteOutcome.NOT_CONFIGURED
        return try {
            api.addSongToPlaylist(playlistId, songId)
            refreshPlaylistDetail(playlistId)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "addTrack($playlistId, $songId) failed", e)
            WriteOutcome.FAILED
        }
    }

    /**
     * [position] is the track's current zero-based index within the playlist.
     * Applies the removal to the local cache optimistically first (same
     * convention as [renamePlaylist]) — without this, an offline/failed remove
     * previously did nothing locally either, so the track just silently stayed
     * in the list with zero feedback that the tap even registered.
     */
    suspend fun removeTrack(playlistId: String, position: Int): WriteOutcome {
        val songIds = playlistDao.getSongIdsInOrder(playlistId).toMutableList()
        if (position !in songIds.indices) return WriteOutcome.FAILED
        songIds.removeAt(position)
        playlistDao.replaceTracks(playlistId, songIds.mapIndexed { i, id -> PlaylistTrackEntity(playlistId, i, id) })
        val api = apiHolder.forId(playlistId) ?: return WriteOutcome.NOT_CONFIGURED
        return try {
            api.removeSongFromPlaylist(playlistId, position)
            refreshPlaylistDetail(playlistId)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "removeTrack($playlistId, $position) failed", e)
            WriteOutcome.FAILED
        }
    }

    /**
     * Server-only replay of a previously-locally-applied [removeTrack] — used
     * by [SyncQueueRepository]. Deliberately does NOT touch the local cache
     * (the original [removeTrack] call already applied it there, optimistically,
     * before this replay was even queued) — calling the mutating [removeTrack]
     * again here would remove *a second* track: `position` is only meaningful
     * relative to the order at the moment it was captured, and the local cache
     * has since moved on from that order.
     */
    suspend fun replayRemoveTrack(playlistId: String, position: Int): WriteOutcome {
        val api = apiHolder.forId(playlistId) ?: return WriteOutcome.NOT_CONFIGURED
        return try {
            api.removeSongFromPlaylist(playlistId, position)
            refreshPlaylistDetail(playlistId)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "replayRemoveTrack($playlistId, $position) failed", e)
            WriteOutcome.FAILED
        }
    }

    suspend fun moveTrackUp(playlistId: String, position: Int): WriteOutcome {
        if (position <= 0) return WriteOutcome.FAILED
        return moveTrack(playlistId, position, position - 1)
    }

    suspend fun moveTrackDown(playlistId: String, position: Int): WriteOutcome {
        val count = playlistDao.getSongIdsInOrder(playlistId).size
        if (position >= count - 1) return WriteOutcome.FAILED
        return moveTrack(playlistId, position, position + 1)
    }

    /**
     * No native Subsonic "move" endpoint exists, so a reorder rewrites the whole
     * track list server-side via `createPlaylist`'s playlistId-replace form (see
     * SubsonicApi.reorderPlaylist) — reads the current order back out of the local
     * cache, swaps the two positions, and round-trips the full list. Applies the
     * swap to the local cache optimistically first, same reasoning as [removeTrack].
     */
    private suspend fun moveTrack(playlistId: String, fromPosition: Int, toPosition: Int): WriteOutcome {
        val playlist = playlistDao.getById(playlistId) ?: return WriteOutcome.FAILED
        val songIds = playlistDao.getSongIdsInOrder(playlistId).toMutableList()
        if (fromPosition !in songIds.indices || toPosition !in songIds.indices) return WriteOutcome.FAILED
        val moved = songIds.removeAt(fromPosition)
        songIds.add(toPosition, moved)
        playlistDao.replaceTracks(playlistId, songIds.mapIndexed { i, id -> PlaylistTrackEntity(playlistId, i, id) })
        if (!holdsOnly(playlistId, songIds)) return WriteOutcome.NOT_CONFIGURED
        val api = apiHolder.forId(playlistId) ?: return WriteOutcome.NOT_CONFIGURED
        return try {
            api.reorderPlaylist(playlistId, playlist.name, songIds)
            refreshPlaylistDetail(playlistId)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "moveTrack($playlistId, $fromPosition -> $toPosition) failed", e)
            WriteOutcome.FAILED
        }
    }

    /** [reorderTo] replays a previously-captured full desired order — used by [SyncQueueRepository]'s PLAYLIST_REORDER replay, which already has the exact target list and shouldn't recompute it from (possibly since-changed) current state. */
    suspend fun reorderTo(playlistId: String, songIds: List<String>): WriteOutcome {
        val playlist = playlistDao.getById(playlistId) ?: return WriteOutcome.FAILED
        playlistDao.replaceTracks(playlistId, songIds.mapIndexed { i, id -> PlaylistTrackEntity(playlistId, i, id) })
        if (!holdsOnly(playlistId, songIds)) return WriteOutcome.NOT_CONFIGURED
        val api = apiHolder.forId(playlistId) ?: return WriteOutcome.NOT_CONFIGURED
        return try {
            api.reorderPlaylist(playlistId, playlist.name, songIds)
            refreshPlaylistDetail(playlistId)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            AppLogger.e("PlaylistRepository", "reorderTo($playlistId) failed", e)
            WriteOutcome.FAILED
        }
    }

    private fun SubsonicPlaylist.toEntity() = PlaylistEntity(id, name, songCount, duration)
    private fun SubsonicPlaylistDetail.toEntity() = PlaylistEntity(id, name, songCount, duration)
    private fun PlaylistEntity.toDomain() = Playlist(id, name, songCount, durationSec)
    // SubsonicSong.toTrackEntity() / TrackEntity.toTrack() — see TrackMapping.kt
    // (previously duplicated verbatim from LibraryRepository here).
}

sealed class CreatePlaylistResult {
    data class Created(val id: String) : CreatePlaylistResult()
    data object NotConfigured : CreatePlaylistResult()
    data object Failed : CreatePlaylistResult()
}
