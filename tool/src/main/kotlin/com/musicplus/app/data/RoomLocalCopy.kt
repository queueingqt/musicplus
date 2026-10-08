package com.musicplus.app.data

/** [LocalCopy] over the Room tables: the phone's cached library. */
class RoomLocalCopy(
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
) : LocalCopy {
    override suspend fun setStarred(type: String, id: String, starred: Boolean) = when (type) {
        "artist" -> artistDao.setStarred(id, starred)
        "album" -> albumDao.setStarred(id, starred)
        else -> trackDao.setStarred(id, starred)
    }

    override suspend fun playlistName(playlistId: String): String? = playlistDao.getById(playlistId)?.name

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        playlistDao.getById(playlistId)?.let { playlistDao.upsert(it.copy(name = name)) }
    }

    override suspend fun deletePlaylist(playlistId: String) = playlistDao.deleteWithTracks(listOf(playlistId))

    override suspend fun songIds(playlistId: String): List<String> = playlistDao.getSongIdsInOrder(playlistId)

    override suspend fun setSongIds(playlistId: String, songIds: List<String>) {
        playlistDao.replaceTracks(playlistId, songIds.mapIndexed { i, id -> PlaylistTrackEntity(playlistId, i, id) })
        recountIfPhone(playlistId, songIds)
    }

    override suspend fun adoptPlaylist(placeholderId: String, name: String) =
        playlistDao.upsert(PlaylistEntity(placeholderId, name, songCount = 0, durationSec = 0))

    override suspend fun reassignPlaylist(oldId: String, newId: String) = playlistDao.reassignPlaylistId(oldId, newId)

    /** The total length of [songIds], read from the cached tracks. */
    suspend fun durationOf(songIds: List<String>): Int =
        songIds.chunked(500).sumOf { chunk -> trackDao.getByIds(chunk).sumOf { it.durationSec } }

    /** A Phone Only playlist's count and length come from what it holds; there is no server to tell it. */
    private suspend fun recountIfPhone(playlistId: String, songIds: List<String>) {
        if (!ServerScope.isPhone(playlistId)) return
        val entity = playlistDao.getById(playlistId) ?: return
        playlistDao.upsert(entity.copy(songCount = songIds.size, durationSec = durationOf(songIds)))
    }
}
