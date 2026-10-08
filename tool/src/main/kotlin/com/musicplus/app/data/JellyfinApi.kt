package com.musicplus.app.data

import io.ktor.client.call.body
import java.io.File

/**
 * Endpoint-level Jellyfin calls — the Jellyfin-side twin of [SubsonicApi], implementing the same [MusicApi] contract.
 * Kept separate from [JellyfinClient] so the raw request machinery doesn't get lost in endpoint-specific parameter
 * lists, same split as Subsonic's.
 *
 * **This class speaks the app's scoped ids, and the wire speaks the server's own** (see [ServerScope]) — identical
 * discipline to [SubsonicApi]: every id returned is scoped to [serverId] here, and unscoped again before it reaches
 * [JellyfinClient]. Jellyfin's own ids are plain GUIDs (`28f5e904b031119b1d4cba35b2285904` — no dashes in this
 * server's real responses), which never contain `:`, so [ServerScope]'s separator assumption holds unchanged.
 *
 * No [CapabilityRegistry] probing here (unlike [SubsonicApi]) — a real Jellyfin server's feature set is fully known
 * from its own documented API, not guessed at the way an undocumented Subsonic mount (Bandcamp) has to be. See
 * [CapabilityRegistry]'s own doc for where Jellyfin servers get their capabilities hardcoded instead.
 */
class JellyfinApi(
    override val serverId: String,
    private val client: JellyfinClient,
) : MusicApi, PlayReporter {

    override val playReporter: PlayReporter get() = this

    /**
     * A real Jellyfin server's whole feature set is documented by its own API: nothing here is undocumented the way an arbitrary
     * Subsonic mount (Bandcamp) can be, so there is nothing to find out by asking. SCROBBLE is deliberately not offered: it means
     * "this server takes a Subsonic-style `scrobble.view` relay", which Jellyfin has no equivalent of (its own playback reporting is
     * [playReporter], unconditional, and never goes through this capability).
     */
    override suspend fun probeCapabilities(): Map<Capability, Support> = mapOf(
        Capability.STAR to Support.YES,
        Capability.SCROBBLE to Support.NO,
        Capability.LYRICS to Support.YES,
        Capability.PLAYLIST_WRITE to Support.YES,
    )

    private val ids = ScopedIds(serverId, "JellyfinApi")
    private fun scopeId(id: String) = ids.scope(id)
    private fun native(id: String) = ids.native(id)

    private val userId: String get() = client.userId

    companion object {
        private const val DEFAULT_ITEM_FIELDS = "ChildCount,Genres,CumulativeRunTimeTicks"
    }

    override suspend fun checkLogin(): Result<Unit> = client.checkLogin()
    override suspend fun ping(): Result<Unit> = client.ping()

    // --- Item -> shared MusicApi DTOs. The one JellyfinItem shape covers artists/albums/songs alike (told apart by Type) — see JellyfinDtos.kt's doc. ---

    private fun JellyfinItem.toApiArtist() = ApiArtist(
        id = scopeId(Id),
        name = Name.orEmpty(),
        coverArtId = artCoverId(),
        albumCount = ChildCount ?: 0,
        starred = UserData?.IsFavorite ?: false,
    )

    private fun JellyfinItem.toApiAlbum() = ApiAlbum(
        id = scopeId(Id),
        name = Name.orEmpty(),
        artist = AlbumArtist,
        artistId = AlbumArtists.firstOrNull()?.Id?.let { scopeId(it) } ?: ArtistItems.firstOrNull()?.Id?.let { scopeId(it) },
        coverArtId = artCoverId(),
        songCount = ChildCount ?: 0,
        durationSec = RunTimeTicks.ticksToSeconds(),
        year = ProductionYear,
        genre = Genres.firstOrNull(),
        starred = UserData?.IsFavorite ?: false,
    )

    private fun JellyfinItem.toApiSong() = ApiSong(
        id = scopeId(Id),
        title = Name.orEmpty(),
        album = Album,
        albumId = AlbumId?.let { scopeId(it) },
        artist = AlbumArtist ?: ArtistItems.firstOrNull()?.Name,
        artistId = ArtistItems.firstOrNull()?.Id?.let { scopeId(it) } ?: AlbumArtists.firstOrNull()?.Id?.let { scopeId(it) },
        trackNumber = IndexNumber,
        durationSec = RunTimeTicks.ticksToSeconds(),
        coverArtId = artCoverId(),
        suffix = Container,
        sizeBytes = MediaSources.firstOrNull()?.Size,
        starred = UserData?.IsFavorite ?: false,
    )

    private fun JellyfinItem.toApiPlaylist(durationSecOverride: Int? = null) = ApiPlaylist(
        id = scopeId(Id),
        name = Name.orEmpty(),
        songCount = ChildCount ?: 0,
        durationSec = durationSecOverride ?: CumulativeRunTimeTicks.ticksToSeconds(),
        coverArtId = artCoverId(),
    )

    /**
     * The item's own art if it has any, else its album's — a song usually has no `ImageTags` of its own and inherits
     * the album's cover, same convention Subsonic's `coverArt` field already follows (often the album's id, not a
     * separate per-song image). Null (not the album id) when *nothing* has art, so [AlbumArtRepository] doesn't
     * bother asking a server for an image that was never going to exist.
     */
    private fun JellyfinItem.artCoverId(): String? = when {
        ImageTags.containsKey("Primary") -> scopeId(Id)
        AlbumId != null -> scopeId(AlbumId)
        else -> null
    }

    /**
     * `GET /Items` only returns [JellyfinItem.ChildCount], [JellyfinItem.Genres] and
     * [JellyfinItem.CumulativeRunTimeTicks] when explicitly asked for via `fields=` — left off, they come back
     * null/empty rather than omitted-but-inferrable, which silently produced "0 tracks"/"0 albums" everywhere
     * (caught testing against the real Jellyfin test server, not a guess). Requested unconditionally here since
     * they're cheap scalar fields, not a payload like [JellyfinItem.MediaSources] which stays opt-in per call.
     */
    private suspend fun items(params: List<Pair<String, String>>, extraFields: String? = null): List<JellyfinItem> {
        val fields = listOfNotNull(DEFAULT_ITEM_FIELDS, extraFields).joinToString(",")
        return client.get("/Items", listOf("userId" to userId, "fields" to fields) + params) { it.body<JellyfinItemsResponse>() }.Items
    }

    private suspend fun oneItem(id: String): JellyfinItem? =
        items(listOf("ids" to native(id))).firstOrNull()

    override suspend fun getArtists(): List<ApiArtist> =
        items(listOf("includeItemTypes" to "MusicArtist", "recursive" to "true", "sortBy" to "SortName")).map { it.toApiArtist() }

    override suspend fun getArtist(id: String): ApiArtistDetail? {
        val artist = oneItem(id) ?: return null
        val albums = items(
            listOf("includeItemTypes" to "MusicAlbum", "recursive" to "true", "artistIds" to native(id), "sortBy" to "SortName"),
        ).map { it.toApiAlbum() }
        return ApiArtistDetail(
            id = scopeId(artist.Id),
            name = artist.Name.orEmpty(),
            coverArtId = artist.artCoverId(),
            albumCount = albums.size,
            starred = artist.UserData?.IsFavorite ?: false,
            albums = albums,
        )
    }

    override suspend fun getAlbumList(size: Int, offset: Int): List<ApiAlbum> =
        items(
            listOf(
                "includeItemTypes" to "MusicAlbum", "recursive" to "true",
                "sortBy" to "SortName", "startIndex" to offset.toString(), "limit" to size.toString(),
            ),
        ).map { it.toApiAlbum() }

    override suspend fun getAlbum(id: String): ApiAlbumDetail? {
        val album = oneItem(id) ?: return null
        val songs = items(
            listOf("parentId" to native(id), "includeItemTypes" to "Audio", "sortBy" to "IndexNumber"),
        ).map { it.toApiSong() }
        return ApiAlbumDetail(
            id = scopeId(album.Id),
            name = album.Name.orEmpty(),
            artist = album.AlbumArtist,
            artistId = album.AlbumArtists.firstOrNull()?.Id?.let { scopeId(it) },
            coverArtId = album.artCoverId(),
            songCount = songs.size,
            durationSec = songs.sumOf { it.durationSec },
            year = album.ProductionYear,
            genre = album.Genres.firstOrNull(),
            starred = album.UserData?.IsFavorite ?: false,
            songs = songs,
        )
    }

    /** Asks for [JellyfinMediaSource] data too — not requested on bulk browse calls, which never need a byte size (see [MediaIntegrity], the only reader of [ApiSong.sizeBytes]). */
    override suspend fun getSong(id: String): ApiSong? =
        items(listOf("ids" to native(id)), extraFields = "MediaSources").firstOrNull()?.toApiSong()

    override suspend fun search(query: String, artistCount: Int, albumCount: Int, songCount: Int): ApiSearchResult {
        if (query.isBlank()) return ApiSearchResult()
        val artists = items(listOf("includeItemTypes" to "MusicArtist", "recursive" to "true", "searchTerm" to query, "limit" to artistCount.toString()))
        val albums = items(listOf("includeItemTypes" to "MusicAlbum", "recursive" to "true", "searchTerm" to query, "limit" to albumCount.toString()))
        val songs = items(listOf("includeItemTypes" to "Audio", "recursive" to "true", "searchTerm" to query, "limit" to songCount.toString()))
        return ApiSearchResult(artists.map { it.toApiArtist() }, albums.map { it.toApiAlbum() }, songs.map { it.toApiSong() })
    }

    override suspend fun getSongsPage(songCount: Int, songOffset: Int): List<ApiSong> =
        items(
            listOf(
                "includeItemTypes" to "Audio", "recursive" to "true",
                "sortBy" to "SortName", "startIndex" to songOffset.toString(), "limit" to songCount.toString(),
            ),
        ).map { it.toApiSong() }

    /**
     * Three separate calls, not one `includeItemTypes=MusicArtist,MusicAlbum,Audio` — confirmed against the real test
     * server that combining a multi-value `includeItemTypes` with `filters=IsFavorite` silently returns zero results
     * (each type filtered alone works correctly), which made this always report "nothing starred" and, through
     * [LibraryRepository.mirrorStarred]'s clear-what's-missing-locally logic, wiped every local favorite flag for a
     * Jellyfin server on each refresh. Not documented behavior, found by testing.
     */
    override suspend fun getStarred(): ApiStarred {
        val favoriteParams = listOf("recursive" to "true", "filters" to "IsFavorite")
        return ApiStarred(
            artists = items(listOf("includeItemTypes" to "MusicArtist") + favoriteParams).map { it.toApiArtist() },
            albums = items(listOf("includeItemTypes" to "MusicAlbum") + favoriteParams).map { it.toApiAlbum() },
            songs = items(listOf("includeItemTypes" to "Audio") + favoriteParams).map { it.toApiSong() },
        )
    }

    override suspend fun star(id: String) {
        client.post<Unit>("/UserFavoriteItems/${native(id)}", listOf("userId" to userId)) {}
    }

    override suspend fun unstar(id: String) {
        client.delete("/UserFavoriteItems/${native(id)}", listOf("userId" to userId))
    }

    override suspend fun getPlaylists(): List<ApiPlaylist> =
        items(listOf("includeItemTypes" to "Playlist", "recursive" to "true", "sortBy" to "SortName")).map { it.toApiPlaylist() }

    override suspend fun getPlaylist(id: String): ApiPlaylistDetail? {
        val playlist = oneItem(id) ?: return null
        val entries = playlistItems(native(id)).map { it.toApiSong() }
        return ApiPlaylistDetail(
            id = scopeId(playlist.Id),
            name = playlist.Name.orEmpty(),
            songCount = entries.size,
            durationSec = entries.sumOf { it.durationSec },
            coverArtId = playlist.artCoverId(),
            entries = entries,
        )
    }

    /** The playlist's own ordered entries — a separate call from [items] since `/Playlists/{id}/Items` (not `/Items`) is what carries each entry's [JellyfinItem.PlaylistItemId]-shaped identity, needed by [removeSongFromPlaylist]. */
    private suspend fun playlistItems(nativePlaylistId: String): List<JellyfinItem> =
        client.get("/Playlists/$nativePlaylistId/Items", listOf("userId" to userId)) { it.body<JellyfinItemsResponse>() }.Items

    override suspend fun createPlaylist(name: String, songIds: List<String>): ApiPlaylistDetail? {
        val result = client.post(
            "/Playlists",
            body = JellyfinCreatePlaylistRequest(Name = name, Ids = songIds.map { native(it) }, UserId = userId),
        ) { it.body<JellyfinPlaylistCreationResult>() }
        val newId = result.Id ?: return null
        return getPlaylist(scopeId(newId))
    }

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        client.post<Unit>("/Playlists/${native(playlistId)}", body = JellyfinRenamePlaylistRequest(name)) {}
    }

    override suspend fun addSongToPlaylist(playlistId: String, songId: String) {
        client.post<Unit>("/Playlists/${native(playlistId)}/Items", listOf("ids" to native(songId), "userId" to userId)) {}
    }

    /**
     * [songIndex] is a position in the *current* server order, same contract as [SubsonicApi.removeSongFromPlaylist]
     * — resolved to Jellyfin's own per-entry id with a fresh read first, since Jellyfin (unlike Subsonic) tells
     * playlist entries apart by that id, not by position, precisely so the same song can appear twice.
     */
    override suspend fun removeSongFromPlaylist(playlistId: String, songIndex: Int) {
        val entries = playlistItems(native(playlistId))
        val entryId = entries.getOrNull(songIndex)?.PlaylistItemId ?: return
        client.delete("/Playlists/${native(playlistId)}/Items", listOf("entryIds" to entryId))
    }

    /** No single "replace the track list" call on Jellyfin (unlike Subsonic's playlistId-replace form of `createPlaylist`) — drops every current entry, then adds [songIds] back in order. */
    override suspend fun reorderPlaylist(playlistId: String, name: String, songIds: List<String>) {
        val nativeId = native(playlistId)
        val currentEntryIds = playlistItems(nativeId).mapNotNull { it.PlaylistItemId }
        if (currentEntryIds.isNotEmpty()) client.delete("/Playlists/$nativeId/Items", listOf("entryIds" to currentEntryIds.joinToString(",")))
        if (songIds.isNotEmpty()) {
            client.post<Unit>("/Playlists/$nativeId/Items", listOf("ids" to songIds.joinToString(",") { native(it) }, "userId" to userId)) {}
        }
    }

    override suspend fun deletePlaylist(id: String) {
        client.delete("/Items", listOf("ids" to native(id)))
    }

    override fun streamUrl(songId: String, maxBitRateKbps: Int?): String =
        client.endpointUrl("/Audio/${native(songId)}/stream.mp3", streamParams(maxBitRateKbps))

    override suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int?, lease: FetchGate.Lease?) {
        client.downloadToFile("/Audio/${native(songId)}/stream.mp3", destination, streamParams(maxBitRateKbps), lease)
    }

    /** `static=true` with no other transcode params serves the original file untouched — a capped bitrate switches on transcoding to mp3 instead. Same convention as [SubsonicApi.streamUrl]/[streamToFile] one level up: capped means mp3 regardless of source format. */
    private fun streamParams(maxBitRateKbps: Int?): List<Pair<String, String>> =
        if (maxBitRateKbps == null) {
            listOf("static" to "true")
        } else {
            listOf("audioCodec" to "mp3", "audioBitRate" to (maxBitRateKbps * 1000).toString())
        }

    override suspend fun downloadToFile(songId: String, destination: File, lease: FetchGate.Lease?) {
        client.downloadToFile("/Audio/${native(songId)}/stream", destination, listOf("static" to "true"), lease)
    }

    override suspend fun coverArtBytes(coverArtId: String, size: Int): ByteArray =
        client.getBytes("/Items/${native(coverArtId)}/Images/Primary", listOf("maxWidth" to size.toString(), "maxHeight" to size.toString()))

    override suspend fun getLyrics(songId: String): List<ApiLyricEntry> {
        val response = try {
            client.get("/Audio/${native(songId)}/Lyrics", emptyList()) { it.body<JellyfinLyricResponse>() }
        } catch (e: JellyfinApiException) {
            // 404 = genuinely no lyrics for this track, same "ok but nothing" convention as Subsonic's empty lyricsList.
            if (e.code == 404) return emptyList() else throw e
        }
        if (response.Lyrics.isEmpty()) return emptyList()
        val synced = response.Lyrics.any { it.Start != null }
        return listOf(ApiLyricEntry(kind = null, synced = synced, lines = response.Lyrics.map { ApiLyricLine(it.Start.ticksToMs().takeIf { synced }, it.Text) }))
    }

    /** Jellyfin has no dedicated "similar artists" concept this app uses elsewhere — empty rather than a best-effort guess. */
    override suspend fun getSimilarArtists(artistId: String, count: Int): List<ApiArtist> = emptyList()

    /** [artistName] is unused — Jellyfin's equivalent is id-keyed, unlike Subsonic's; see the interface doc. Every song by this artist, by play count. */
    override suspend fun getTopSongs(artistId: String, artistName: String, count: Int): List<ApiSong> =
        items(
            listOf(
                "includeItemTypes" to "Audio", "recursive" to "true", "artistIds" to native(artistId),
                "sortBy" to "PlayCount", "sortOrder" to "Descending", "limit" to count.toString(),
            ),
        ).map { it.toApiSong() }

    // --- Playback-progress reporting. Deliberately not part of MusicApi — see its class doc for why this is not the
    // same thing as Subsonic's scrobble(), and [PlaybackRepository] for where these are actually called from
    // (unconditionally, never gated by AppSettingsRepository.scrobblingEnabled). ---

    /** Once, when a song starts (or resumes into) playing — drives this server's own "now playing" and starts its resume-position tracking for the session. */
    override suspend fun reportPlaybackStart(songId: String, positionMs: Long, playSessionId: String) {
        client.post<Unit>("/Sessions/Playing", body = playbackInfo(songId, positionMs, isPaused = false, playSessionId)) {}
    }

    /** Periodically while a song remains current (playing or paused) — see [PlaybackRepository]'s reporting cadence. Also what actually saves the resume position server-side. */
    override suspend fun reportPlaybackProgress(songId: String, positionMs: Long, isPaused: Boolean, playSessionId: String) {
        client.post<Unit>("/Sessions/Playing/Progress", body = playbackInfo(songId, positionMs, isPaused, playSessionId)) {}
    }

    /** Once, when a song stops being current (the queue moves on, playback is cleared, or the app is releasing the player). */
    override suspend fun reportPlaybackStopped(songId: String, positionMs: Long, playSessionId: String) {
        client.post<Unit>("/Sessions/Playing/Stopped", body = playbackInfo(songId, positionMs, isPaused = false, playSessionId)) {}
    }

    private fun playbackInfo(songId: String, positionMs: Long, isPaused: Boolean, playSessionId: String) = JellyfinPlaybackInfoRequest(
        ItemId = native(songId),
        PositionTicks = positionMs.msToTicks(),
        IsPaused = isPaused,
        PlaySessionId = playSessionId,
    )
}
