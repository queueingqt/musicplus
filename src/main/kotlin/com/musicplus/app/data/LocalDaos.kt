package com.musicplus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    /** Every list below is limited to [serverIds], the servers whose content is being shown (see [ServerScope]). */
    @Query("SELECT * FROM artists WHERE ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY name COLLATE NOCASE")
    fun observeAll(serverIds: List<String>): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE starred = 1 AND ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY name COLLATE NOCASE")
    fun observeFavorites(serverIds: List<String>): Flow<List<ArtistEntity>>

    /** See [TrackDao.search]'s doc — same local-cache fallback, same LIKE-match shape. */
    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' AND ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) LIMIT :limit")
    suspend fun search(query: String, serverIds: List<String>, limit: Int = 50): List<ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("UPDATE artists SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("SELECT id FROM artists WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getIdsFor(serverId: String): List<String>

    /** One server's snapshot for [mirrorFromServer]'s diff — never observed, only read once per refresh pass. It must be per-server: a mirror pass removes whatever it doesn't see, and must never see another server's rows as missing. */
    @Query("SELECT * FROM artists WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getAllFor(serverId: String): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ArtistEntity>

    @Query("SELECT id FROM artists WHERE starred = 1 AND ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getStarredIds(serverId: String): List<String>

    @Query("UPDATE artists SET starred = 0 WHERE id IN (:ids)")
    suspend fun clearStarred(ids: List<String>)

    @Query("DELETE FROM artists WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY name COLLATE NOCASE")
    fun observeAll(serverIds: List<String>): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year IS NULL, year")
    fun observeByArtist(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE starred = 1 AND ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY name COLLATE NOCASE")
    fun observeFavorites(serverIds: List<String>): Flow<List<AlbumEntity>>

    /** See [TrackDao.search]'s doc — same local-cache fallback, same LIKE-match shape. */
    @Query("SELECT * FROM albums WHERE (name LIKE '%' || :query || '%' OR artistName LIKE '%' || :query || '%') AND ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) LIMIT :limit")
    suspend fun search(query: String, serverIds: List<String>, limit: Int = 50): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("UPDATE albums SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("SELECT id FROM albums WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getIdsFor(serverId: String): List<String>

    /** See [ArtistDao.getAllFor]. */
    @Query("SELECT * FROM albums WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getAllFor(serverId: String): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AlbumEntity>

    @Query("SELECT id FROM albums WHERE starred = 1 AND ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getStarredIds(serverId: String): List<String>

    @Query("UPDATE albums SET starred = 0 WHERE id IN (:ids)")
    suspend fun clearStarred(ids: List<String>)

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY title COLLATE NOCASE")
    fun observeAll(serverIds: List<String>): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY trackNumber IS NULL, trackNumber")
    fun observeByAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE starred = 1 AND ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY title COLLATE NOCASE")
    fun observeFavorites(serverIds: List<String>): Flow<List<TrackEntity>>

    /**
     * [LibraryRepository.search]'s offline/failed-request fallback — the live
     * search hits Subsonic's own `search3` (relevance-ranked, full-catalog,
     * not just what's already cached), but that's unusable when offline or
     * when the configured server is unreachable (e.g. a Tailscale-hosted
     * server with Tailscale disconnected — the exact case reported live,
     * 2026-09-18, that this fallback exists for). A plain `LIKE` match
     * against whatever's already in Room is worse (substring-only, no
     * relevance ranking, and only covers tracks/albums/artists actually
     * cached locally) but far better than the blank results this screen
     * showed before. `limit` matches search3's own default page size so the
     * two code paths feel similar in scale.
     */
    @Query("SELECT * FROM tracks WHERE (title LIKE '%' || :query || '%' OR artistName LIKE '%' || :query || '%') AND ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) LIMIT :limit")
    suspend fun search(query: String, serverIds: List<String>, limit: Int = 50): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("SELECT id FROM tracks WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getIdsFor(serverId: String): List<String>

    /** See [ArtistDao.getAllFor]. */
    @Query("SELECT * FROM tracks WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getAllFor(serverId: String): List<TrackEntity>

    @Query("SELECT id FROM tracks WHERE starred = 1 AND ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getStarredIds(serverId: String): List<String>

    @Query("UPDATE tracks SET starred = 0 WHERE id IN (:ids)")
    suspend fun clearStarred(ids: List<String>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists WHERE ${ServerScope.SQL_SERVER_OF_ID} IN (:serverIds) ORDER BY name COLLATE NOCASE")
    fun observeAll(serverIds: List<String>): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT id FROM playlists WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getIdsFor(serverId: String): List<String>

    /** See [ArtistDao.getAllFor]. */
    @Query("SELECT * FROM playlists WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun getAllFor(serverId: String): List<PlaylistEntity>

    @Query("DELETE FROM playlist_tracks WHERE playlistId IN (:ids)")
    suspend fun clearTracksFor(ids: List<String>)

    @Query("DELETE FROM playlists WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Playlists the server no longer lists, with their membership rows — see [mirrorFromServer]. */
    @Transaction
    suspend fun deleteWithTracks(ids: List<String>) {
        clearTracksFor(ids)
        deleteByIds(ids)
    }

    @Query(
        """
        SELECT tracks.* FROM tracks
        INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.songId
        WHERE playlist_tracks.playlistId = :playlistId
        ORDER BY playlist_tracks.position
        """,
    )
    fun observeTracks(playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT songId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getSongIdsInOrder(playlistId: String): List<String>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracks(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    /** Wholesale re-sync of one playlist's membership/order after a server refresh. */
    @Transaction
    suspend fun replaceTracks(playlistId: String, tracks: List<PlaylistTrackEntity>) {
        clearTracks(playlistId)
        insertTracks(tracks)
    }

    @Query("UPDATE playlists SET id = :newId WHERE id = :oldId")
    suspend fun reassignId(oldId: String, newId: String)

    @Query("UPDATE playlist_tracks SET playlistId = :newId WHERE playlistId = :oldId")
    suspend fun reassignTracksPlaylistId(oldId: String, newId: String)

    /**
     * Swaps a locally-generated placeholder playlist id for the real server id
     * once an offline "create playlist" mutation finally syncs — see
     * [SyncQueueRepository]'s PLAYLIST_CREATE replay. Both the playlist row
     * itself and its membership rows move together in one transaction so the
     * UI never observes a moment where the playlist exists but looks empty
     * (or vice versa).
     */
    @Transaction
    suspend fun reassignPlaylistId(oldId: String, newId: String) {
        reassignTracksPlaylistId(oldId, newId)
        reassignId(oldId, newId)
    }

    @Query("DELETE FROM playlists")
    suspend fun deleteAllPlaylists()

    @Query("DELETE FROM playlist_tracks")
    suspend fun deleteAllPlaylistTracks()

    @Transaction
    suspend fun deleteAll() {
        deleteAllPlaylistTracks()
        deleteAllPlaylists()
    }
}

@Dao
interface PendingMutationDao {
    @Query("SELECT * FROM pending_mutations ORDER BY id ASC")
    fun observeAll(): Flow<List<PendingMutationEntity>>

    @Query("SELECT * FROM pending_mutations ORDER BY id ASC")
    suspend fun getAllInOrder(): List<PendingMutationEntity>

    @Query("SELECT COUNT(*) FROM pending_mutations")
    fun observeCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_mutations WHERE type = :type AND targetId = :targetId)")
    fun observePendingForTarget(type: String, targetId: String): Flow<Boolean>

    /** Every target with a still-queued mutation of [type] — a refresh must not overwrite (or undo) what the user just did offline. */
    @Query("SELECT targetId FROM pending_mutations WHERE type = :type")
    suspend fun getTargetIdsByType(type: String): List<String>

    @Insert
    suspend fun insert(mutation: PendingMutationEntity): Long

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Drops every still-pending mutation targeting [targetId] — used when a playlist is deleted, so a queued add-track/rename/etc against it isn't replayed against a playlist that's already gone. */
    @Query("DELETE FROM pending_mutations WHERE targetId = :targetId")
    suspend fun deleteForTarget(targetId: String)

    /** Retargets every still-pending mutation from a placeholder id to the real server id — see PLAYLIST_CREATE replay. */
    @Query("UPDATE pending_mutations SET targetId = :newTargetId WHERE targetId = :oldTargetId")
    suspend fun reassignTarget(oldTargetId: String, newTargetId: String)

    @Query("UPDATE pending_mutations SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String?)

    /** Drops one server's still-pending edits (it was removed, so they can never be sent). */
    @Query("DELETE FROM pending_mutations WHERE substr(targetId, 1, instr(targetId, ':') - 1) = :serverId")
    suspend fun deleteForServer(serverId: String)

    /** Discards every still-pending mutation, synced or not — see LocalDataRepository's doc for why callers must warn about this first. */
    @Query("DELETE FROM pending_mutations")
    suspend fun deleteAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY queuedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    suspend fun getBySongId(songId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    fun observeBySongId(songId: String): Flow<DownloadEntity?>

    /** Used by DownloadRepository.retryFailed() — every download that gave up after MAX_DOWNLOAD_ATTEMPTS, re-armed on the next app start. */
    /** Every download row of one server, whatever its state. */
    @Query("SELECT * FROM downloads WHERE substr(songId, 1, instr(songId, ':') - 1) = :serverId")
    suspend fun getForServer(serverId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: DownloadStatus): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun delete(songId: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    fun observeQueue(): Flow<List<QueueItemEntity>>

    @Transaction
    suspend fun replaceQueue(songIds: List<String>) {
        clear()
        insertAll(songIds.mapIndexed { index, id -> QueueItemEntity(position = index, songId = id) })
    }

    /** One server's queued songs after [position] (the ones not yet played), for when the server is removed. */
    @Query("DELETE FROM queue_items WHERE position > :position AND substr(songId, 1, instr(songId, ':') - 1) = :serverId")
    suspend fun deleteAfter(serverId: String, position: Int)

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)
}

/**
 * What a removed server leaves behind, deleted in one transaction: one change notification for every list, instead of one
 * per batch (a removal used to make each list re-run its whole query twenty-odd times while the next batch was writing).
 */
@Dao
interface ServerCleanupDao {
    /**
     * Deletes [serverId]'s library. With [keepDownloads], the songs that have a finished download stay, and so do the
     * albums and artists those songs belong to. Its playlists always go.
     */
    @Transaction
    suspend fun pruneServer(serverId: String, keepDownloads: Boolean) {
        if (keepDownloads) deleteTracksWithoutDownload(serverId) else deleteTracks(serverId)
        deleteUnusedAlbums(serverId)
        deleteUnusedArtists(serverId)
        deletePlaylistTracks(serverId)
        deletePlaylists(serverId)
    }

    @Query("DELETE FROM tracks WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun deleteTracks(serverId: String)

    @Query(
        "DELETE FROM tracks WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId AND id NOT IN " +
            "(SELECT songId FROM downloads WHERE status = 'COMPLETE' AND substr(songId, 1, instr(songId, ':') - 1) = :serverId)",
    )
    suspend fun deleteTracksWithoutDownload(serverId: String)

    @Query("DELETE FROM albums WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId AND id NOT IN (SELECT albumId FROM tracks WHERE albumId IS NOT NULL)")
    suspend fun deleteUnusedAlbums(serverId: String)

    @Query("DELETE FROM artists WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId AND id NOT IN (SELECT artistId FROM tracks WHERE artistId IS NOT NULL)")
    suspend fun deleteUnusedArtists(serverId: String)

    @Query("DELETE FROM playlist_tracks WHERE substr(playlistId, 1, instr(playlistId, ':') - 1) = :serverId")
    suspend fun deletePlaylistTracks(serverId: String)

    @Query("DELETE FROM playlists WHERE ${ServerScope.SQL_SERVER_OF_ID} = :serverId")
    suspend fun deletePlaylists(serverId: String)
}
