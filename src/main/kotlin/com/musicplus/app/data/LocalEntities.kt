package com.musicplus.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of the server's ID3 library (artists/albums/tracks), plus what's
 * favorited, downloaded, and queued. IDs are the server's own Subsonic ids, so
 * cache rows can be upserted directly from API responses without id translation.
 */

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverArtId: String?,
    val albumCount: Int,
    val starred: Boolean,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artistId: String?,
    val artistName: String?,
    val coverArtId: String?,
    val songCount: Int,
    val durationSec: Int,
    val year: Int?,
    val genre: String?,
    val starred: Boolean,
)

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val albumId: String?,
    val albumName: String?,
    val artistId: String?,
    val artistName: String?,
    val trackNumber: Int?,
    val durationSec: Int,
    val coverArtId: String?,
    val suffix: String?,
    val starred: Boolean,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val songCount: Int,
    val durationSec: Int,
)

/**
 * Membership + ordering join table — [position] is zero-based (matches `getPlaylist`'s
 * `entry` order, and `SubsonicApi.removeSongFromPlaylist`'s `songIndexToRemove`).
 * PK is (playlistId, position) rather than a surrogate id so a full re-sync
 * (`PlaylistRepository.refreshPlaylistDetail`) can upsert the whole ordered list in
 * one shot via `REPLACE`, same as the other cache tables here.
 */
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val songId: String,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val localFilePath: String?,
    val status: DownloadStatus,
    val queuedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
)

/** The persisted "now playing" queue, so it survives process death / a restart. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val position: Int,
    val songId: String,
)
