package com.musicplus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE starred = 1 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("UPDATE artists SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year IS NULL, year")
    fun observeByArtist(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE starred = 1 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(): Flow<List<AlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("UPDATE albums SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY trackNumber IS NULL, trackNumber")
    fun observeByAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE starred = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artistName LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 50): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

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
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY queuedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    suspend fun getBySongId(songId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    fun observeBySongId(songId: String): Flow<DownloadEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun delete(songId: String)
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

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)
}
