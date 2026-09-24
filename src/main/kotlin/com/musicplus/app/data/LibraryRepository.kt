@file:OptIn(ExperimentalCoroutinesApi::class)

package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Artist
import com.musicplus.app.Track
import com.musicplus.app.WriteOutcome
import com.musicplus.app.data.ServerLabels.labelledBy
import com.thelightphone.sdk.LightConnectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** How long [LibraryRepository.searchStream] waits for one server's live answer before leaving that server with its cached matches. */
private const val SEARCH_TIMEOUT_MS = 6_000L

/** What a search found. */
data class SearchResults(val artists: List<Artist>, val albums: List<Album>, val tracks: List<Track>)

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
 * at read time (see every `toTrack(...)` call below, and
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
    private val apiHolder: ApiLookup,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val pendingMutationDao: PendingMutationDao,
    private val connectivity: LightConnectivity,
    private val downloadRepository: DownloadRepository,
    /** The servers whose content is shown — every list below follows it, so switching servers switches the lists. See [ServerConfigRepository.shownServerIds]. */
    private val shownServerIds: Flow<List<String>>,
    private val serverSyncStatus: ServerSyncStatus,
) {
    fun observeArtists(): Flow<List<Artist>> =
        shownServerIds.flatMapLatest { artistDao.observeAll(it).retryOnTransientDbError() }.map { it.map { entity -> entity.toDomain() } }.labelledBy(ServerLabels::artists)

    fun observeAlbums(): Flow<List<Album>> =
        shownServerIds.flatMapLatest { albumDao.observeAll(it).retryOnTransientDbError() }.map { it.map { entity -> entity.toDomain() } }.labelledBy(ServerLabels::albums)

    fun observeAlbumsByArtist(artistId: String): Flow<List<Album>> =
        albumDao.observeByArtist(artistId).map { it.map { entity -> entity.toDomain() } }

    fun observeTracksByAlbum(albumId: String): Flow<List<Track>> =
        combine(trackDao.observeByAlbum(albumId).retryOnTransientDbError(), downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(byId[it.id]) }
        }

    fun observeFavoriteArtists(): Flow<List<Artist>> =
        shownServerIds.flatMapLatest { artistDao.observeFavorites(it).retryOnTransientDbError() }.map { it.map { entity -> entity.toDomain() } }.labelledBy(ServerLabels::favoriteArtists)

    fun observeFavoriteAlbums(): Flow<List<Album>> =
        shownServerIds.flatMapLatest { albumDao.observeFavorites(it).retryOnTransientDbError() }.map { it.map { entity -> entity.toDomain() } }.labelledBy(ServerLabels::favoriteAlbums)

    fun observeFavoriteTracks(): Flow<List<Track>> =
        combine(shownServerIds.flatMapLatest { trackDao.observeFavorites(it).retryOnTransientDbError() }, downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(byId[it.id]) }
        }.labelledBy(ServerLabels::favoriteTracks)

    /**
     * Every track in the library, not just ones already pulled in via an
     * album/playlist/search visit — see [refreshAllSongs], which this needs
     * to have actually run at least once for the flat "Songs" list to be
     * complete rather than just whatever happened to already be cached.
     */
    fun observeAllTracks(): Flow<List<Track>> =
        combine(shownServerIds.flatMapLatest { trackDao.observeAll(it).retryOnTransientDbError() }, downloadRepository.observeAll()) { entities, downloads ->
            val byId = downloads.associateBy { it.songId }
            entities.map { it.toTrack(byId[it.id]) }
        }.labelledBy(ServerLabels::tracks)

    /** Batch lookup by id, e.g. restoring a persisted queue (issue #27) — order isn't preserved, callers reorder against their own id list. Silently drops any id no longer in the local cache. */
    suspend fun getTracksByIds(ids: List<String>): List<Track> {
        val byId = downloadRepository.observeAll().first().associateBy { it.songId }
        return trackDao.getByIds(ids).map { it.toTrack(byId[it.id]) }
    }

    private val serverRefresh = ServerRefresh(
        isConnected = { connectivity.currentStatus.isConnected },
        apis = apiHolder,
        shownServerIds = shownServerIds,
        onRefreshed = { serverSyncStatus.refreshed(it) },
    )

    /** See [ServerRefresh.run]: shared by every `refresh*` function below, each just names itself (for the failure log) and does its own fetch-then-upsert as [action]. */
    private suspend fun refresh(label: String, ownerId: String? = null, action: suspend (MusicApi) -> Unit) = serverRefresh.run(label, ownerId, action)

    /** See [ServerRefresh.live]. */
    private fun live(api: MusicApi) = serverRefresh.live(api)

    // The four list refreshes below (and PlaylistRepository.refreshPlaylists)
    // all go through mirrorFromServer — see its doc for what they share: only
    // changed rows are written, and what the server has stopped listing is
    // removed, conservatively. ListRefresher decides *when* they run.

    /** When the phone's own heart wins over the server's answer, read fresh for each refresh: see [LocalStars]. Applied by every refresh, lists and details alike. */
    private suspend fun localStars() = LocalStars(pendingMutationDao.pendingFavoriteIds())

    suspend fun refreshArtists() = refresh("refreshArtists") { api ->
        val stars = localStars()
        mirrorFromServer(
            label = "artists",
            idOf = ArtistEntity::id,
            cached = { artistDao.getAllFor(api.serverId).associateBy { it.id } },
            fetchAll = { onPage -> onPage(api.getArtists().map { it.toEntity() }) },
            write = { if (live(api)) artistDao.upsertAll(artistDao.keepingLocalStars(it, stars)) },
            remove = { if (live(api)) artistDao.deleteByIds(it) },
            merge = { fetched, local -> if (local != null && stars.wins(fetched.id)) fetched.copy(starred = local.starred) else fetched },
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
        val stars = localStars()
        mirrorFromServer(
            label = "albums",
            idOf = AlbumEntity::id,
            cached = { albumDao.getAllFor(api.serverId).associateBy { it.id } },
            fetchAll = { onPage ->
                pageThrough(
                    idOf = AlbumEntity::id,
                    fetchPage = { offset -> api.getAlbumList(ALBUM_LIST_PAGE_SIZE, offset).map { it.toEntity() } },
                    onPage = onPage,
                )
            },
            write = { if (live(api)) albumDao.upsertAll(albumDao.keepingLocalStars(it, stars)) },
            remove = { if (live(api)) albumDao.deleteByIds(it) },
            merge = { fetched, local -> if (local != null && stars.wins(fetched.id)) fetched.copy(starred = local.starred) else fetched },
        )
    }

    suspend fun refreshArtistDetail(artistId: String) = refresh("refreshArtistDetail($artistId)", ownerId = artistId) { api ->
        val detail = api.getArtist(artistId) ?: return@refresh
        if (!live(api)) return@refresh
        albumDao.upsertAll(albumDao.keepingLocalStars(detail.albums.map { it.toEntity() }, localStars()))
    }

    suspend fun refreshAlbumDetail(albumId: String) = refresh("refreshAlbumDetail($albumId)", ownerId = albumId) { api ->
        val detail = api.getAlbum(albumId) ?: return@refresh
        if (!live(api)) return@refresh
        trackDao.upsertAll(trackDao.keepingLocalStars(detail.songs.map { it.toTrackEntity() }, localStars()))
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
        val stars = localStars()
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
            write = { if (live(api)) trackDao.upsertAll(trackDao.keepingLocalStars(it, stars)) },
            remove = { if (live(api)) trackDao.deleteByIds(it) },
            merge = { fetched, local -> if (local != null && stars.wins(fetched.id)) fetched.copy(starred = local.starred) else fetched },
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
        // A server without favorites has nothing to bring down, and its hearts live on the phone only: mirroring an
        // empty (or unanswerable) list would erase them.
        if (Capabilities.cannot(api.serverId, Capability.STAR)) return@refresh
        val starred = api.getStarred()
        val pending = pendingMutationDao.pendingFavoriteIds()
        mirrorStarred(
            label = "favorite artists",
            fetched = starred.artists.map { it.toEntity().copy(starred = true) },
            idOf = ArtistEntity::id,
            cachedByIds = { artistDao.getByIds(it) },
            write = { artistDao.upsertAll(it) },
            localStarredIds = { artistDao.getStarredIds(api.serverId) },
            clearStarred = { artistDao.clearStarred(it) },
            pending = pending,
        )
        mirrorStarred(
            label = "favorite albums",
            fetched = starred.albums.map { it.toEntity().copy(starred = true) },
            idOf = AlbumEntity::id,
            cachedByIds = { albumDao.getByIds(it) },
            write = { albumDao.upsertAll(it) },
            localStarredIds = { albumDao.getStarredIds(api.serverId) },
            clearStarred = { albumDao.clearStarred(it) },
            pending = pending,
        )
        mirrorStarred(
            label = "favorite songs",
            fetched = starred.songs.map { it.toTrackEntity().copy(starred = true) },
            idOf = TrackEntity::id,
            cachedByIds = { trackDao.getByIds(it) },
            write = { trackDao.upsertAll(it) },
            localStarredIds = { trackDao.getStarredIds(api.serverId) },
            clearStarred = { trackDao.clearStarred(it) },
            pending = pending,
        )
    }

    /**
     * Search across every server that is on. What is already cached shows at once (the same LIKE-match fallback a
     * server that cannot be reached always got, see [TrackDao.search]'s doc, so a server that is down still
     * contributes what is cached); then each server's live `search3` result replaces that server's cached slice as
     * it arrives (relevance-ranked and full-catalog, not just what is cached). A server that has not answered within
     * [SEARCH_TIMEOUT_MS] is left with its cached slice, so one slow server never holds the results up. Offline, only
     * the cached results are emitted. Forgejo issue #45 is why the cached fallback exists at all.
     */
    fun searchStream(query: String): Flow<SearchResults> = channelFlow {
        if (query.isBlank()) {
            send(SearchResults(emptyList(), emptyList(), emptyList()))
            return@channelFlow
        }
        val servers = shownServerIds.first()
        val local = searchLocal(query)
        send(labelled(local))
        if (!connectivity.currentStatus.isConnected) return@channelFlow

        val downloadsById = downloadRepository.observeAll().first().associateBy { it.songId }
        val stars = localStars()
        val live = HashMap<String, SearchResults>()
        val lock = Mutex()
        fun merged(): SearchResults {
            fun <T> pick(id: String, fromLive: (SearchResults) -> List<T>, fromLocal: List<T>, serverOf: (T) -> String?) =
                live[id]?.let(fromLive) ?: fromLocal.filter { serverOf(it) == id }
            // Interleaved by rank, each server's best match first, so no server's results sit below another's whole page.
            return labelled(
                SearchResults(
                    interleave(servers.map { id -> pick(id, { it.artists }, local.artists) { ServerScope.serverOf(it.id) } }),
                    interleave(servers.map { id -> pick(id, { it.albums }, local.albums) { ServerScope.serverOf(it.id) } }),
                    interleave(servers.map { id -> pick(id, { it.tracks }, local.tracks) { ServerScope.serverOf(it.id) } }),
                ),
            )
        }
        coroutineScope {
            for (id in servers) {
                launch {
                    val api = apiHolder.forServer(id) ?: return@launch
                    val result = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        try {
                            api.search(query)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("LibraryRepository", "search(\"$query\") failed on server $id, keeping its cached matches", e)
                            null
                        }
                    } ?: return@launch
                    lock.withLock {
                        live[id] = SearchResults(
                            artistDao.keepingLocalStars(result.artists.map { it.toEntity() }, stars).map { it.toDomain() },
                            albumDao.keepingLocalStars(result.albums.map { it.toEntity() }, stars).map { it.toDomain() },
                            trackDao.keepingLocalStars(result.songs.map { it.toTrackEntity() }, stars).map { it.toTrack(downloadsById[it.id]) },
                        )
                        send(merged())
                    }
                }
            }
        }
    }

    /** First of each list, then second of each, and so on. */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val longest = lists.maxOfOrNull { it.size } ?: return emptyList()
        return buildList { for (rank in 0 until longest) for (list in lists) list.getOrNull(rank)?.let { add(it) } }
    }

    private fun labelled(results: SearchResults) =
        SearchResults(ServerLabels.artists(results.artists), ServerLabels.albums(results.albums), ServerLabels.tracks(results.tracks))

    private suspend fun searchLocal(query: String): SearchResults {
        val servers = shownServerIds.first()
        val downloadsById = downloadRepository.observeAll().first().associateBy { it.songId }
        return SearchResults(
            artistDao.search(query, servers).map { it.toDomain() },
            albumDao.search(query, servers).map { it.toDomain() },
            trackDao.search(query, servers).map { it.toTrack(downloadsById[it.id]) },
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
            artistDao.keepingLocalStars(api.getSimilarArtists(artistId).map { it.toEntity() }, localStars()).map { it.toDomain() }
        } catch (e: Exception) {
            AppLogger.e("LibraryRepository", "getSimilarArtists($artistId) failed", e)
            emptyList()
        }
    }

    /** ArtistDetailScreen's "Top songs" section — same on-demand, not-Room-cached shape as [getSimilarArtists] above. [artistName] (not an id) — see [SubsonicApi.getTopSongs]'s doc for why. */
    suspend fun getTopSongs(artistId: String, artistName: String): List<Track> {
        // The artist's own server: with several servers on, "the active one" is meaningless, and another server has never heard of them.
        val api = apiHolder.forId(artistId) ?: return emptyList()
        return try {
            val downloadsById = downloadRepository.observeAll().first().associateBy { it.songId }
            trackDao.keepingLocalStars(api.getTopSongs(artistId, artistName).map { it.toTrackEntity() }, localStars()).map { it.toTrack(downloadsById[it.id]) }
        } catch (e: Exception) {
            AppLogger.e("LibraryRepository", "getTopSongs(\"$artistName\") failed", e)
            emptyList()
        }
    }

    /** The ids of the exact same song (same artist, title and album) held by other servers than [songId]'s own. */
    suspend fun sameSongElsewhere(songId: String): List<String> {
        val song = trackDao.getByIds(listOf(songId)).firstOrNull() ?: return emptyList()
        return trackDao.findSame(song.title, song.artistName.orEmpty(), song.albumName.orEmpty(), ServerScope.serverOf(songId).orEmpty()).map { it.id }
    }

    /** Everything hearted on [serverId] as (type, id), for [SyncQueueRepository.sendPhoneOnlyFavorites]. */
    suspend fun localFavorites(serverId: String): List<Pair<String, String>> =
        artistDao.getStarredIds(serverId).map { "artist" to it } +
            albumDao.getStarredIds(serverId).map { "album" to it } +
            trackDao.getStarredIds(serverId).map { "track" to it }

    private fun ApiArtist.toEntity() = ArtistEntity(id, name, coverArtId, albumCount, starred)
    private fun ApiAlbum.toEntity() = AlbumEntity(id, name, artistId, artist, coverArtId, songCount, durationSec, year, genre, starred)

    private fun ArtistEntity.toDomain() = Artist(id, name, coverArtId, albumCount, starred)
    private fun AlbumEntity.toDomain() = Album(id, name, artistId, artistName, coverArtId, songCount, durationSec, year, starred)
    // ApiSong.toTrackEntity() / TrackEntity.toTrack() — see TrackMapping.kt.
}
