@file:OptIn(ExperimentalCoroutinesApi::class)

package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Artist
import com.musicplus.app.Track
import com.musicplus.app.WriteOutcome
import com.thelightphone.sdk.LightConnectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** How many songs [LibraryRepository.refreshAllSongs] asks for per page — large enough that a typical library finishes in a small handful of requests, small enough that each individual request/upsert stays quick. */
private const val ALL_SONGS_PAGE_SIZE = 500

/** Same idea for [LibraryRepository.refreshAlbumList]; 500 is also the most Navidrome's `getAlbumList2` will return per call. */
private const val ALBUM_LIST_PAGE_SIZE = 500

/**
 * Cache-then-network access to the server's ID3 library. Room is the source of
 * truth for what the UI observes; `refresh*` functions pull from Subsonic and
 * upsert, so screens stay responsive (and usable offline) between refreshes.
 *
 * Track.downloadStatus/localFilePath are joined against [downloadRepository]
 * at read time (see every `toTrack(apiHolder, ...)` call below, and
 * TrackMapping.kt's [toTrack] doc) — previously always false/null coming out
 * of this repository, before it took a real dependency on the download queue
 * instead of leaving every caller to separately combine the two Flows
 * itself. Confirmed live, 2026-09-18 architecture review + this session's
 * own /grilling pass: this wasn't just a cosmetic gap — a downloaded track
 * played from any screen other than the download flow itself silently
 * streamed over the network instead of using the local file, since
 * localFilePath was always null too.
 */
class LibraryRepository(
    private val apiHolder: SubsonicApiHolder,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val pendingMutationDao: PendingMutationDao,
    private val connectivity: LightConnectivity,
    private val downloadRepository: DownloadRepository,
    /** The servers whose content is shown — every list below follows it, so switching servers switches the lists. See [ServerConfigRepository.shownServerIds]. */
    private val shownServerIds: Flow<List<String>>,
) {
    fun observeArtists(): Flow<List<Artist>> =
        shownServerIds.flatMapLatest { artistDao.observeAll(it) }.map { it.map { entity -> entity.toDomain() } }

    fun observeAlbums(): Flow<List<Album>> =
        shownServerIds.flatMapLatest { albumDao.observeAll(it) }.map { it.map { entity -> entity.toDomain() } }

    fun observeAlbumsByArtist(artistId: String): Flow<List<Album>> =
        albumDao.observeByArtist(artistId).map { it.map { entity -> entity.toDomain() } }

    // includeCoverArt = false — AlbumDetailScreen shows the album's own art
    // once at the top, never per track row; see toTrack's doc for why this
    // matters (a real, measured cost, not a theoretical one).
    fun observeTracksByAlbum(albumId: String): Flow<List<Track>> =
        combine(trackDao.observeByAlbum(albumId), downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(apiHolder, byId[it.id], includeCoverArt = false) }
        }

    fun observeFavoriteArtists(): Flow<List<Artist>> =
        shownServerIds.flatMapLatest { artistDao.observeFavorites(it) }.map { it.map { entity -> entity.toDomain() } }

    fun observeFavoriteAlbums(): Flow<List<Album>> =
        shownServerIds.flatMapLatest { albumDao.observeFavorites(it) }.map { it.map { entity -> entity.toDomain() } }

    // includeCoverArt = false — Favorites' Tracks section shows no per-row
    // art (showFavorite = false is TrackRow's only override there); see
    // toTrack's doc.
    fun observeFavoriteTracks(): Flow<List<Track>> =
        combine(shownServerIds.flatMapLatest { trackDao.observeFavorites(it) }, downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(apiHolder, byId[it.id], includeCoverArt = false) }
        }

    /**
     * Every track in the library, not just ones already pulled in via an
     * album/playlist/search visit — see [refreshAllSongs], which this needs
     * to have actually run at least once for the flat "Songs" list to be
     * complete rather than just whatever happened to already be cached.
     */
    // includeCoverArt = false — the flat Songs list shows no per-row art;
    // see toTrack's doc. This is the screen that made the cost visible in
    // the first place (several thousand tracks vs. Albums'/Artists' low
    // hundreds), confirmed live 2026-09-18.
    fun observeAllTracks(): Flow<List<Track>> =
        combine(shownServerIds.flatMapLatest { trackDao.observeAll(it) }, downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(apiHolder, byId[it.id], includeCoverArt = false) }
        }

    /** Batch lookup by id, e.g. restoring a persisted queue (issue #27) — order isn't preserved, callers reorder against their own id list. Silently drops any id no longer in the local cache. */
    suspend fun getTracksByIds(ids: List<String>): List<Track> {
        val byId = downloadRepository.observeAll().first().associateBy { it.songId }
        return trackDao.getByIds(ids).map { it.toTrack(apiHolder, byId[it.id]) }
    }

    /**
     * No-ops (leaves the cache as-is) when offline, not yet configured, or the
     * network call itself fails — callers just keep showing cached data.
     * Shared by every `refresh*` function below — each just names itself (for
     * the failure log) and does its own fetch-then-upsert as [action].
     *
     * The `connectivity.currentStatus.isConnected` check alone isn't enough to
     * guarantee this is safe to call unguarded: it only reports whether *some*
     * network is up, not whether the configured server is actually reachable
     * (e.g. a Tailscale-hosted server when Tailscale isn't currently connected
     * resolves as "online" generally but fails DNS for that one host). A crash
     * here previously took down the whole app on launch — confirmed on-device,
     * `UnresolvedAddressException` from `HomeScreenViewModel.onScreenShow`'s
     * unguarded `refreshAlbumList()`/`refreshArtists()` calls, 2026-09-17.
     */
    private suspend fun refresh(label: String, ownerId: String? = null, action: suspend (SubsonicApi) -> Unit) {
        if (!connectivity.currentStatus.isConnected) return
        // A refresh of one artist/album goes to the server that owns it; a list refresh goes to the active server.
        val api = (if (ownerId != null) apiHolder.forId(ownerId) else apiHolder.get()) ?: return
        try {
            action(api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cache left as-is deliberately — see this function's own doc above.
            AppLogger.e("LibraryRepository", "$label failed", e)
        }
    }

    // The four list refreshes below (and PlaylistRepository.refreshPlaylists)
    // all go through mirrorFromServer — see its doc for what they share: only
    // changed rows are written, and what the server has stopped listing is
    // removed, conservatively. ListRefresher decides *when* they run.

    /** Favorites the person toggled offline that are still waiting to sync — a refresh must not overwrite them with the server's older answer. */
    private suspend fun pendingFavoriteIds(): Set<String> =
        pendingMutationDao.getTargetIdsByType("FAVORITE").toHashSet()

    suspend fun refreshArtists() = refresh("refreshArtists") { api ->
        val pending = pendingFavoriteIds()
        mirrorFromServer(
            label = "artists",
            idOf = ArtistEntity::id,
            cached = { artistDao.getAllFor(api.serverId).associateBy { it.id } },
            fetchAll = { onPage -> onPage(api.getArtists().map { it.toEntity() }) },
            write = { artistDao.upsertAll(it) },
            remove = { artistDao.deleteByIds(it) },
            merge = { fetched, local -> if (local != null && fetched.id in pending) fetched.copy(starred = local.starred) else fetched },
        )
    }

    /**
     * Every album, not a first page of them — this used to ask for 50 and stop,
     * so an album list past that size only ever held whatever else had been
     * pulled in by visiting artists. Always the full alphabetical listing:
     * anything else (newest, recent, ...) is a subset, and [mirrorFromServer]
     * would take everything outside the subset for deleted.
     */
    suspend fun refreshAlbumList() = refresh("refreshAlbumList") { api ->
        val pending = pendingFavoriteIds()
        mirrorFromServer(
            label = "albums",
            idOf = AlbumEntity::id,
            cached = { albumDao.getAllFor(api.serverId).associateBy { it.id } },
            fetchAll = { onPage ->
                pageThrough(
                    idOf = AlbumEntity::id,
                    fetchPage = { offset -> api.getAlbumList("alphabeticalByName", ALBUM_LIST_PAGE_SIZE, offset).map { it.toEntity() } },
                    onPage = onPage,
                )
            },
            write = { albumDao.upsertAll(it) },
            remove = { albumDao.deleteByIds(it) },
            merge = { fetched, local -> if (local != null && fetched.id in pending) fetched.copy(starred = local.starred) else fetched },
        )
    }

    suspend fun refreshArtistDetail(artistId: String) = refresh("refreshArtistDetail($artistId)", ownerId = artistId) { api ->
        val detail = api.getArtist(artistId) ?: return@refresh
        albumDao.upsertAll(detail.album.map { it.toEntity() })
    }

    suspend fun refreshAlbumDetail(albumId: String) = refresh("refreshAlbumDetail($albumId)", ownerId = albumId) { api ->
        val detail = api.getAlbum(albumId) ?: return@refresh
        trackDao.upsertAll(detail.song.map { it.toTrackEntity() })
    }

    /**
     * Pages through [SubsonicApi.getSongsPage] to the end (see [pageThrough]),
     * writing as it goes — needed because [observeAllTracks]'s flat "Songs"
     * list is otherwise only as complete as whichever albums/playlists/
     * searches happen to have been visited already. A real library can be
     * several thousand tracks; writing page by page means the list is already
     * populating while later pages are still loading, rather than one long
     * wait before anything shows. Songs the person has downloaded are never
     * removed, even if the server no longer lists them — the file is theirs.
     */
    suspend fun refreshAllSongs() = refresh("refreshAllSongs") { api ->
        val pending = pendingFavoriteIds()
        mirrorFromServer(
            label = "songs",
            idOf = TrackEntity::id,
            cached = { trackDao.getAllFor(api.serverId).associateBy { it.id } },
            fetchAll = { onPage ->
                pageThrough(
                    idOf = TrackEntity::id,
                    fetchPage = { offset -> api.getSongsPage(songCount = ALL_SONGS_PAGE_SIZE, songOffset = offset).map { it.toTrackEntity() } },
                    onPage = onPage,
                )
            },
            write = { trackDao.upsertAll(it) },
            remove = { trackDao.deleteByIds(it) },
            merge = { fetched, local -> if (local != null && fetched.id in pending) fetched.copy(starred = local.starred) else fetched },
            keep = { candidates ->
                val downloaded = downloadRepository.observeAll().first().mapTo(HashSet()) { it.songId }
                candidates.filterTo(HashSet()) { it in downloaded }
            },
        )
    }

    /**
     * The Favorites page's refresh: one `getStarred2` call gives every starred
     * artist, album and song, so favorites made (or undone) from another
     * client show up without walking the whole library — see [mirrorStarred].
     */
    suspend fun refreshFavorites() = refresh("refreshFavorites") { api ->
        val starred = api.getStarred()
        val pending = pendingFavoriteIds()
        mirrorStarred(
            label = "favorite artists",
            fetched = starred.artist.map { it.toEntity().copy(starred = true) },
            idOf = ArtistEntity::id,
            cachedByIds = { artistDao.getByIds(it) },
            write = { artistDao.upsertAll(it) },
            localStarredIds = { artistDao.getStarredIds(api.serverId) },
            clearStarred = { artistDao.clearStarred(it) },
            pending = pending,
        )
        mirrorStarred(
            label = "favorite albums",
            fetched = starred.album.map { it.toEntity().copy(starred = true) },
            idOf = AlbumEntity::id,
            cachedByIds = { albumDao.getByIds(it) },
            write = { albumDao.upsertAll(it) },
            localStarredIds = { albumDao.getStarredIds(api.serverId) },
            clearStarred = { albumDao.clearStarred(it) },
            pending = pending,
        )
        mirrorStarred(
            label = "favorite songs",
            fetched = starred.song.map { it.toTrackEntity().copy(starred = true) },
            idOf = TrackEntity::id,
            cachedByIds = { trackDao.getByIds(it) },
            write = { trackDao.upsertAll(it) },
            localStarredIds = { trackDao.getStarredIds(api.serverId) },
            clearStarred = { trackDao.clearStarred(it) },
            pending = pending,
        )
    }

    /**
     * Server-side `search3` when reachable; falls back to a local Room
     * `LIKE`-match (see [TrackDao.search]'s doc for why, and its own
     * tradeoffs vs. the real thing) when offline, not configured, or the
     * live request itself fails. Forgejo issue #45, reported live
     * 2026-09-18: this used to just return empty results in all three
     * cases — useless against a server that's only temporarily unreachable
     * (e.g. Tailscale disconnected), even for a track that's already fully
     * downloaded and sitting right there in the local cache.
     */
    suspend fun search(query: String): Triple<List<Artist>, List<Album>, List<Track>> {
        if (query.isBlank()) return Triple(emptyList(), emptyList(), emptyList())
        val api = apiHolder.get()
        if (connectivity.currentStatus.isConnected && api != null) {
            try {
                val result = api.search(query)
                val downloadsById = downloadRepository.observeAll().first().associateBy { it.songId }
                return Triple(
                    result.artist.map { it.toEntity().toDomain() },
                    result.album.map { it.toEntity().toDomain() },
                    result.song.map { it.toTrackEntity().toTrack(apiHolder, downloadsById[it.id]) },
                )
            } catch (e: Exception) {
                AppLogger.e("LibraryRepository", "search(\"$query\") failed, falling back to local cache", e)
            }
        }
        return searchLocal(query)
    }

    private suspend fun searchLocal(query: String): Triple<List<Artist>, List<Album>, List<Track>> {
        val servers = shownServerIds.first()
        val downloadsById = downloadRepository.observeAll().first().associateBy { it.songId }
        return Triple(
            artistDao.search(query, servers).map { it.toDomain() },
            albumDao.search(query, servers).map { it.toDomain() },
            trackDao.search(query, servers).map { it.toTrack(apiHolder, downloadsById[it.id]) },
        )
    }

    /**
     * ArtistDetailScreen's "Similar artists" section — fetched on demand
     * (only when the section is actually expanded), not cached in Room: this
     * doesn't need to survive offline the way the main library cache does.
     * No `connectivity.currentStatus.isConnected` pre-check the way the
     * refresh* functions above have — those guard a background cache
     * refresh where silently staying on stale data offline is the right
     * call; this is a direct, on-demand user action instead, so an offline/
     * failed call just falls through the same try/catch every other network
     * call here already uses. See [SubsonicApi.getSimilarArtists]'s doc for
     * which real endpoint this is (`getArtistInfo2`, not `getSimilarSongs2`).
     */
    suspend fun getSimilarArtists(artistId: String): List<Artist> {
        val api = apiHolder.forId(artistId) ?: return emptyList()
        return try {
            api.getSimilarArtists(artistId).map { it.toEntity().toDomain() }
        } catch (e: Exception) {
            AppLogger.e("LibraryRepository", "getSimilarArtists($artistId) failed", e)
            emptyList()
        }
    }

    /** ArtistDetailScreen's "Top songs" section — same on-demand, not-Room-cached shape as [getSimilarArtists] above. [artistName] (not an id) — see [SubsonicApi.getTopSongs]'s doc for why. includeCoverArt = false — TopSongRow shows no per-row art; see toTrack's doc. */
    suspend fun getTopSongs(artistName: String): List<Track> {
        val api = apiHolder.get() ?: return emptyList()
        return try {
            val downloadsById = downloadRepository.observeAll().first().associateBy { it.songId }
            api.getTopSongs(artistName).map { it.toTrackEntity().toTrack(apiHolder, downloadsById[it.id], includeCoverArt = false) }
        } catch (e: Exception) {
            AppLogger.e("LibraryRepository", "getTopSongs(\"$artistName\") failed", e)
            emptyList()
        }
    }

    suspend fun setArtistFavorite(id: String, favorite: Boolean) = setFavorite(id, favorite) { artistDao.setStarred(id, favorite) }
    suspend fun setAlbumFavorite(id: String, favorite: Boolean) = setFavorite(id, favorite) { albumDao.setStarred(id, favorite) }
    suspend fun setTrackFavorite(id: String, favorite: Boolean) = setFavorite(id, favorite) { trackDao.setStarred(id, favorite) }

    /**
     * Returns [WriteOutcome.FAILED] (rather than silently swallowing, as this
     * used to) so [SyncQueueRepository] knows to queue this for a later retry —
     * confirmed live that a failed/offline favorite toggle otherwise showed as
     * permanently favorited locally with zero indication the server never got
     * it (issue #24).
     */
    private suspend inline fun setFavorite(id: String, favorite: Boolean, updateLocal: () -> Unit): WriteOutcome {
        updateLocal() // optimistic — reflect it immediately, reconcile on next refresh if this fails
        val api = apiHolder.forId(id) ?: return WriteOutcome.NOT_CONFIGURED
        return try {
            if (favorite) api.star(id) else api.unstar(id)
            WriteOutcome.SUCCESS
        } catch (e: Exception) {
            // Local state already reflects the tap; a later refresh (or a
            // successful sync-queue replay) reconciles.
            AppLogger.e("LibraryRepository", "setFavorite($id, $favorite) failed", e)
            WriteOutcome.FAILED
        }
    }

    private fun SubsonicArtist.toEntity() = ArtistEntity(id, name, coverArt, albumCount, starred != null)
    private fun SubsonicAlbum.toEntity() = AlbumEntity(id, name, artistId, artist, coverArt, songCount, duration, year, genre, starred != null)

    // `apiHolder.peek()` — best-effort: returns null (no cover art URL yet) until a
    // suspend refresh has run at least once and resolved the client. Acceptable for
    // a stub; screens should trigger a refresh on first show (see HomeScreen).
    private fun ArtistEntity.toDomain() = Artist(id, name, coverArtId?.let { apiHolder.peekFor(it)?.coverArtUrl(it) }, albumCount, starred)
    private fun AlbumEntity.toDomain() = Album(id, name, artistId, artistName, coverArtId?.let { apiHolder.peekFor(it)?.coverArtUrl(it) }, songCount, durationSec, year, starred)
    // SubsonicSong.toTrackEntity() / TrackEntity.toTrack() — see TrackMapping.kt.
}
