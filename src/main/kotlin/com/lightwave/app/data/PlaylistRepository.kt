package com.lightwave.app.data

import com.lightwave.app.Playlist
import com.lightwave.app.Track
import com.thelightphone.sdk.LightConnectivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cache-then-network access to server playlists, mirroring [LibraryRepository]'s
 * observe/refresh pattern. Split into its own class (rather than folded into
 * LibraryRepository) because membership/reorder is genuinely more than one more
 * `observeX`/`refreshX` pair — creating, renaming, deleting, and especially
 * reordering (no native Subsonic "move" endpoint — see [SubsonicApi.reorderPlaylist])
 * all need to read the current server-round-tripped track order back out, not just
 * upsert a flat list.
 */
class PlaylistRepository(
    private val apiHolder: SubsonicApiHolder,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val connectivity: LightConnectivity,
) {
    fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAll().map { it.map { entity -> entity.toDomain() } }

    fun observePlaylist(playlistId: String): Flow<Playlist?> =
        playlistDao.observeById(playlistId).map { it?.toDomain() }

    fun observeTracks(playlistId: String): Flow<List<Track>> =
        playlistDao.observeTracks(playlistId).map { entities ->
            entities.map { it.toDomain(downloaded = false, localFilePath = null) }
        }

    /**
     * No-op (leaves the cache as-is) when offline, not yet configured, or the
     * network call itself fails — same convention as LibraryRepository (see its
     * class-level refresh-failure doc for why the connectivity check alone
     * isn't sufficient).
     */
    suspend fun refreshPlaylists() {
        if (!connectivity.currentStatus.isConnected) return
        val api = apiHolder.get() ?: return
        try {
            playlistDao.upsertAll(api.getPlaylists().map { it.toEntity() })
        } catch (e: Exception) {
            // Swallowed deliberately — see class-level refresh-failure doc above.
        }
    }

    suspend fun refreshPlaylistDetail(playlistId: String) {
        if (!connectivity.currentStatus.isConnected) return
        val api = apiHolder.get() ?: return
        try {
            val detail = api.getPlaylist(playlistId) ?: return
            playlistDao.upsert(detail.toEntity())
            trackDao.upsertAll(detail.entry.map { it.toEntity() })
            playlistDao.replaceTracks(
                playlistId,
                detail.entry.mapIndexed { index, song -> PlaylistTrackEntity(playlistId, index, song.id) },
            )
        } catch (e: Exception) {
            // Swallowed deliberately — see class-level refresh-failure doc above.
        }
    }

    /** Returns the new playlist's server id, or null if offline/not configured/the call fails. */
    suspend fun createPlaylist(name: String): String? {
        val api = apiHolder.get() ?: return null
        return try {
            val created = api.createPlaylist(name) ?: return null
            playlistDao.upsert(created.toEntity())
            created.id
        } catch (e: Exception) {
            null
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        val api = apiHolder.get() ?: return
        // Optimistic local rename so the title updates immediately even if the
        // network call below is slow/offline/fails; refreshPlaylistDetail
        // reconciles it on the next successful refresh either way.
        playlistDao.getById(playlistId)?.let { playlistDao.upsert(it.copy(name = name)) }
        try {
            api.renamePlaylist(playlistId, name)
            refreshPlaylistDetail(playlistId)
        } catch (e: Exception) {
            // Swallowed deliberately — see class-level refresh-failure doc above.
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        val api = apiHolder.get()
        try {
            if (api != null) api.deletePlaylist(playlistId)
        } catch (e: Exception) {
            // Swallowed deliberately — still remove the local copy below even if
            // the server call failed, matching this function's own local-delete intent.
        }
        playlistDao.delete(playlistId)
    }

    suspend fun addTrack(playlistId: String, songId: String) {
        val api = apiHolder.get() ?: return
        try {
            api.addSongToPlaylist(playlistId, songId)
            refreshPlaylistDetail(playlistId)
        } catch (e: Exception) {
            // Swallowed deliberately — see class-level refresh-failure doc above.
        }
    }

    /** [position] is the track's current zero-based index within the playlist. */
    suspend fun removeTrack(playlistId: String, position: Int) {
        val api = apiHolder.get() ?: return
        try {
            api.removeSongFromPlaylist(playlistId, position)
            refreshPlaylistDetail(playlistId)
        } catch (e: Exception) {
            // Swallowed deliberately — see class-level refresh-failure doc above.
        }
    }

    suspend fun moveTrackUp(playlistId: String, position: Int) {
        if (position <= 0) return
        moveTrack(playlistId, position, position - 1)
    }

    suspend fun moveTrackDown(playlistId: String, position: Int) {
        val count = playlistDao.getSongIdsInOrder(playlistId).size
        if (position >= count - 1) return
        moveTrack(playlistId, position, position + 1)
    }

    /**
     * No native Subsonic "move" endpoint exists, so a reorder rewrites the whole
     * track list server-side via `createPlaylist`'s playlistId-replace form (see
     * SubsonicApi.reorderPlaylist) — reads the current order back out of the local
     * cache, swaps the two positions, and round-trips the full list.
     */
    private suspend fun moveTrack(playlistId: String, fromPosition: Int, toPosition: Int) {
        val api = apiHolder.get() ?: return
        val playlist = playlistDao.getById(playlistId) ?: return
        val songIds = playlistDao.getSongIdsInOrder(playlistId).toMutableList()
        if (fromPosition !in songIds.indices || toPosition !in songIds.indices) return
        val moved = songIds.removeAt(fromPosition)
        songIds.add(toPosition, moved)
        try {
            api.reorderPlaylist(playlistId, playlist.name, songIds)
            refreshPlaylistDetail(playlistId)
        } catch (e: Exception) {
            // Swallowed deliberately — see class-level refresh-failure doc above.
        }
    }

    private fun SubsonicPlaylist.toEntity() = PlaylistEntity(id, name, songCount, duration)
    private fun SubsonicPlaylistDetail.toEntity() = PlaylistEntity(id, name, songCount, duration)
    private fun PlaylistEntity.toDomain() = Playlist(id, name, songCount, durationSec)

    // Duplicated from LibraryRepository rather than shared — both are private
    // extensions scoped to their own class there, matching this codebase's existing
    // per-repository mapping style (see LibraryRepository's own SubsonicSong.toEntity).
    private fun SubsonicSong.toEntity() = TrackEntity(id, title, albumId, album, artistId, artist, track, duration, coverArt, suffix, starred != null)
    private fun TrackEntity.toDomain(downloaded: Boolean, localFilePath: String?) =
        Track(id, title, albumId, albumName, artistId, artistName, trackNumber, durationSec, coverArtId?.let { apiHolder.peek()?.coverArtUrl(it) }, starred, downloaded, localFilePath)
}
