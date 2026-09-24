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
    private val apiHolder: ApiLookup,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val connectivity: LightConnectivity,
    private val downloadRepository: DownloadRepository,
    /** See [LibraryRepository]'s parameter of the same name. */
    private val shownServerIds: Flow<List<String>>,
    private val serverSyncStatus: ServerSyncStatus,
    /** The phone's own copy of a playlist's songs and lengths: where the local half of every playlist write lives (see [LocalFirstWrites]). */
    private val localCopy: RoomLocalCopy,
    /** When the phone's own heart wins over the server's answer, read fresh for each refresh: see [LocalStars]. */
    private val localStars: suspend () -> LocalStars,
) {
    /** The playlists of every server that is on, and the ones kept only on this phone, which are always shown. */
    fun observePlaylists(): Flow<List<Playlist>> =
        shownServerIds.map { it + ServerScope.PHONE }.flatMapLatest { playlistDao.observeAll(it).retryOnTransientDbError() }.map { it.map { entity -> entity.toDomain() } }.labelledBy(ServerLabels::playlists)

    /** A playlist that exists only on this phone, holding [songIds] (which can come from any servers). Returns its id. Nothing is ever sent to a server for it. */
    suspend fun createPhonePlaylist(name: String, songIds: List<String> = emptyList()): String {
        val id = ServerScope.newPhoneId()
        playlistDao.upsert(PlaylistEntity(id, name, songIds.size, localCopy.durationOf(songIds)))
        if (songIds.isNotEmpty()) playlistDao.insertTracks(songIds.mapIndexed { i, songId -> PlaylistTrackEntity(id, i, songId) })
        return id
    }

    /** [wanted], or "<wanted> 2", "<wanted> 3" ... when a Phone Only playlist already has that name. Playlists on servers are told apart by their label, so only Phone Only ones count. */
    suspend fun uniquePhoneName(wanted: String): String {
        val taken = playlistDao.getAllFor(ServerScope.PHONE).mapTo(HashSet()) { it.name.trim().lowercase() }
        if (wanted.trim().lowercase() !in taken) return wanted
        var n = 2
        while ("$wanted $n".trim().lowercase() in taken) n++
        return "$wanted $n"
    }

    /**
     * A new Phone Only copy of [playlistId] with [songId] added at the end. The original is not touched. Reads the
     * original's songs fresh first (a playlist that was never opened has none cached), and returns null instead of a
     * copy that would be missing some of them.
     */
    suspend fun copyToPhone(playlistId: String, songId: String): String? {
        refreshPlaylistDetail(playlistId)
        val original = playlistDao.getById(playlistId) ?: return null
        val songs = playlistDao.getSongIdsInOrder(playlistId)
        if (songs.size < original.songCount) return null
        return createPhonePlaylist(uniquePhoneName(original.name), songs + songId)
    }

    fun observePlaylist(playlistId: String): Flow<Playlist?> =
        playlistDao.observeById(playlistId).map { it?.toDomain() }

    fun observeTracks(playlistId: String): Flow<List<Track>> =
        combine(playlistDao.observeTracks(playlistId).retryOnTransientDbError(), downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(byId[it.id]) }
        }

    private val serverRefresh = ServerRefresh(
        isConnected = { connectivity.currentStatus.isConnected },
        apis = apiHolder,
        shownServerIds = shownServerIds,
        onRefreshed = { serverSyncStatus.refreshed(it) },
    )

    /** See [ServerRefresh.run], shared with [LibraryRepository]. */
    private suspend fun refresh(label: String, ownerId: String? = null, action: suspend (MusicApi) -> Unit) = serverRefresh.run(label, ownerId, action)

    private fun live(api: MusicApi) = serverRefresh.live(api)

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
        if (!live(api)) return@refresh
        playlistDao.upsert(detail.toEntity())
        trackDao.upsertAll(trackDao.keepingLocalStars(detail.entries.map { it.toTrackEntity() }, localStars()))
        playlistDao.replaceTracks(
            playlistId,
            detail.entries.mapIndexed { index, song -> PlaylistTrackEntity(playlistId, index, song.id) },
        )
    }

    private fun ApiPlaylist.toEntity() = PlaylistEntity(id, name, songCount, durationSec)
    private fun ApiPlaylistDetail.toEntity() = PlaylistEntity(id, name, songCount, durationSec)
    private fun PlaylistEntity.toDomain() = Playlist(id, name, songCount, durationSec)
    // ApiSong.toTrackEntity() / TrackEntity.toTrack() — see TrackMapping.kt
    // (previously duplicated verbatim from LibraryRepository here).
}
