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
    // How many times downloadTrack has tried and failed this song since it was
    // last (re-)queued — see DownloadRepository's MAX_DOWNLOAD_ATTEMPTS. Reset
    // to 0 on every fresh enqueue (manual retry, or the app-start retryFailed()
    // sweep), same convention PendingMutationEntity.attemptCount already uses.
    val attemptCount: Int = 0,
)

/** The persisted "now playing" queue, so it survives process death / a restart. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val position: Int,
    val songId: String,
)

/**
 * A server write that was attempted and failed (offline, or a transient server
 * error), waiting to be replayed — see [SyncQueueRepository]. [type] is a
 * [PendingMutationType] name; [payloadJson] is that type's own small
 * `@Serializable` payload (everything the type needs beyond [targetId]).
 * [targetId] is always "the thing this mutation is about" — a track/album/
 * artist id for a favorite toggle, a playlist id for everything else — kept as
 * its own indexed column (not buried in the JSON) so it's directly queryable:
 * driving a per-item "still syncing" UI indicator, and letting every other
 * still-pending mutation against a given playlist be found/dropped/remapped
 * in one query (see deleting or offline-creating a playlist).
 */
@Entity(tableName = "pending_mutations")
data class PendingMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val targetId: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)
