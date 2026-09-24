package com.musicplus.app.data

import java.io.File

/**
 * Endpoint-level Subsonic calls, one function per REST method actually used by
 * Music +. Kept separate from [SubsonicClient] so the raw request/auth
 * machinery doesn't get lost in endpoint-specific parameter lists.
 *
 * **This class speaks the app's scoped ids, and the wire speaks the server's own** (see [ServerScope]).
 * Everything it returns has its ids already scoped to [serverId], and every id it is handed is unscoped
 * on the way out, so no other code ever sees a server's raw id. An id that belongs to a different server
 * is refused rather than sent, since another server would answer it with somebody else's song or an error.
 */
class SubsonicApi(
    override val serverId: String,
    private val client: SubsonicClient,
    /** Told when a real request shows that this server does or does not offer a feature — see [CapabilityRegistry]. */
    private val learner: CapabilityLearner? = null,
) : MusicApi {

    private fun scopeId(id: String) = ServerScope.scope(serverId, id)

    /**
     * Runs a real request for [capability] and tells the [learner] how it went: a success means the server offers it,
     * and a failure that looks like the endpoint not being there (not a dropped connection, not the server understanding
     * and refusing) makes it check again.
     */
    private suspend inline fun <T> learning(capability: Capability, block: () -> T): T {
        val result = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SubsonicApiException) {
            throw e
        } catch (e: Exception) {
            if (!e.isUnreachable()) learner?.doubted(serverId, capability)
            throw e
        }
        learner?.worked(serverId, capability)
        return result
    }

    /** An ordinary, signed-in question — the control that says a "no" from [probe] means something. See [SubsonicClient.checkLogin]. */
    override suspend fun checkLogin(): Result<Unit> = client.checkLogin()

    /** One harmless request that shows whether this server offers [capability]. Only meaningful right after [checkLogin] succeeded. */
    suspend fun probe(capability: Capability): Support = client.probe(capability.probeMethod, capability.probeParams)

    /** A cheap "is it there" request; the outcome reaches [ServerReachability] through the client. */
    override suspend fun ping(): Result<Unit> = client.ping()

    /** The id as this server knows it. An id that was never scoped is passed through and logged: it means a code path missed the scoping. */
    private fun native(id: String): String {
        val owner = ServerScope.serverOf(id)
        if (owner == null) {
            AppLogger.e("SubsonicApi", "unscoped id \"$id\" sent to server $serverId")
            return id
        }
        require(owner == serverId) { "id $id belongs to server $owner, not $serverId" }
        return ServerScope.nativeOf(id)
    }

    private fun SubsonicArtist.scoped() = copy(id = scopeId(id), coverArt = coverArt?.let { scopeId(it) })
    private fun SubsonicArtistDetail.scoped() =
        copy(id = scopeId(id), coverArt = coverArt?.let { scopeId(it) }, album = album.map { it.scoped() })
    private fun SubsonicAlbum.scoped() =
        copy(id = scopeId(id), artistId = artistId?.let { scopeId(it) }, coverArt = coverArt?.let { scopeId(it) })
    private fun SubsonicAlbumDetail.scoped() =
        copy(id = scopeId(id), artistId = artistId?.let { scopeId(it) }, coverArt = coverArt?.let { scopeId(it) }, song = song.map { it.scoped() })
    private fun SubsonicSong.scoped() =
        copy(id = scopeId(id), albumId = albumId?.let { scopeId(it) }, artistId = artistId?.let { scopeId(it) }, coverArt = coverArt?.let { scopeId(it) })
    private fun SubsonicPlaylist.scoped() = copy(id = scopeId(id), coverArt = coverArt?.let { scopeId(it) })
    private fun SubsonicPlaylistDetail.scoped() =
        copy(id = scopeId(id), coverArt = coverArt?.let { scopeId(it) }, entry = entry.map { it.scoped() })

    // --- Already-scoped Subsonic DTOs -> the shared MusicApi shape. Called right after .scoped(), never on a raw wire DTO. ---
    private fun SubsonicArtist.toApi() = ApiArtist(id, name, coverArt, albumCount, starred != null)
    private fun SubsonicArtistDetail.toApi() = ApiArtistDetail(id, name, coverArt, albumCount, starred != null, album.map { it.toApi() })
    private fun SubsonicAlbum.toApi() = ApiAlbum(id, name, artist, artistId, coverArt, songCount, duration, year, genre, starred != null)
    private fun SubsonicAlbumDetail.toApi() =
        ApiAlbumDetail(id, name, artist, artistId, coverArt, songCount, duration, year, genre, starred != null, song.map { it.toApi() })
    private fun SubsonicSong.toApi() = ApiSong(id, title, album, albumId, artist, artistId, track, duration, coverArt, suffix, size, starred != null)
    private fun SubsonicPlaylist.toApi() = ApiPlaylist(id, name, songCount, duration, coverArt)
    private fun SubsonicPlaylistDetail.toApi() = ApiPlaylistDetail(id, name, songCount, duration, coverArt, entry.map { it.toApi() })
    private fun SubsonicStructuredLyrics.toApi() = ApiLyricEntry(kind, synced, line.map { ApiLyricLine(it.start, it.value) })

    override suspend fun getArtists(): List<ApiArtist> =
        (client.call("getArtists.view").artists?.index?.flatMap { it.artist } ?: emptyList()).map { it.scoped().toApi() }

    override suspend fun getArtist(id: String): ApiArtistDetail? =
        client.call("getArtist.view", listOf("id" to native(id))).artist?.scoped()?.toApi()

    override suspend fun getAlbumList(size: Int, offset: Int): List<ApiAlbum> =
        client.call(
            "getAlbumList2.view",
            listOf("type" to "alphabeticalByName", "size" to size.toString(), "offset" to offset.toString()),
        ).albumList2?.album.orEmpty().map { it.scoped().toApi() }

    override suspend fun getAlbum(id: String): ApiAlbumDetail? =
        client.call("getAlbum.view", listOf("id" to native(id))).album?.scoped()?.toApi()

    override suspend fun getSong(id: String): ApiSong? =
        client.call("getSong.view", listOf("id" to native(id))).song?.scoped()?.toApi()

    override suspend fun search(query: String, artistCount: Int, albumCount: Int, songCount: Int): ApiSearchResult =
        client.call(
            "search3.view",
            listOf(
                "query" to query,
                "artistCount" to artistCount.toString(),
                "albumCount" to albumCount.toString(),
                "songCount" to songCount.toString(),
            ),
        ).searchResult3.let { result ->
            if (result == null) ApiSearchResult() else ApiSearchResult(
                artists = result.artist.map { it.scoped().toApi() },
                albums = result.album.map { it.scoped().toApi() },
                songs = result.song.map { it.scoped().toApi() },
            )
        }

    /**
     * One page of the *entire* song library, for the flat "Songs" browse list
     * — there's no dedicated "getAllSongs" Subsonic endpoint, so this is the
     * documented way every real client gets one: `search3` with an empty
     * query matches every song (confirmed against Navidrome's own search
     * behavior, not guessed), `artistCount`/`albumCount` zeroed out since
     * only songs are wanted here, and `songOffset` for paging through a
     * library too large for one call. See [LibraryRepository.refreshAllSongs]
     * for the paging loop.
     */
    override suspend fun getSongsPage(songCount: Int, songOffset: Int): List<ApiSong> =
        client.call(
            "search3.view",
            listOf(
                "query" to "",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to songCount.toString(),
                "songOffset" to songOffset.toString(),
            ),
        ).searchResult3?.song.orEmpty().map { it.scoped().toApi() }

    override suspend fun getStarred(): ApiStarred =
        learning(Capability.STAR) { client.call("getStarred2.view") }.starred2.let { starred ->
            if (starred == null) ApiStarred() else ApiStarred(
                artists = starred.artist.map { it.scoped().toApi() },
                albums = starred.album.map { it.scoped().toApi() },
                songs = starred.song.map { it.scoped().toApi() },
            )
        }

    /** [id] may be a song, album, or artist id — Subsonic stars any of the three the same way. */
    override suspend fun star(id: String) {
        learning(Capability.STAR) { client.call("star.view", listOf("id" to native(id))) }
    }

    override suspend fun unstar(id: String) {
        learning(Capability.STAR) { client.call("unstar.view", listOf("id" to native(id))) }
    }

    override suspend fun getPlaylists(): List<ApiPlaylist> =
        client.call("getPlaylists.view").playlists?.playlist.orEmpty().map { it.scoped().toApi() }

    override suspend fun getPlaylist(id: String): ApiPlaylistDetail? =
        client.call("getPlaylist.view", listOf("id" to native(id))).playlist?.scoped()?.toApi()

    /**
     * Creates a new playlist ([playlistId] omitted), or replaces an existing one's
     * entire track list when [playlistId] is supplied — confirmed against
     * Navidrome's actual server source (core/playlists/playlists.go, `Create()`):
     * `pls.Tracks = nil` followed by `pls.AddMediaFilesByID(ids)`, i.e. a genuine
     * replace, not a merge/append. That playlistId-replace form is how
     * [reorderPlaylist] rewrites track order below, since the Subsonic API has no
     * dedicated "move" endpoint.
     */
    override suspend fun createPlaylist(name: String, songIds: List<String>): ApiPlaylistDetail? =
        createPlaylistOrReplace(name, songIds, playlistId = null)

    /** [reorderPlaylist]'s playlistId-replace form shares this — see its own doc. */
    private suspend fun createPlaylistOrReplace(name: String, songIds: List<String>, playlistId: String?): ApiPlaylistDetail? {
        val params = buildList {
            if (playlistId != null) add("playlistId" to native(playlistId))
            add("name" to name)
            songIds.forEach { add("songId" to native(it)) }
        }
        return learning(Capability.PLAYLIST_WRITE) { client.call("createPlaylist.view", params) }.playlist?.scoped()?.toApi()
    }

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        learning(Capability.PLAYLIST_WRITE) { client.call("updatePlaylist.view", listOf("playlistId" to native(playlistId), "name" to name)) }
    }

    override suspend fun addSongToPlaylist(playlistId: String, songId: String) {
        learning(Capability.PLAYLIST_WRITE) { client.call("updatePlaylist.view", listOf("playlistId" to native(playlistId), "songIdToAdd" to native(songId))) }
    }

    /**
     * [songIndex] is the song's position within the playlist's current track list
     * (as returned by [getPlaylist]'s `entry` order) — zero-based, confirmed
     * against Navidrome's actual server source (core/playlists/playlists.go,
     * `Update()`): `positions[i] = strconv.Itoa(idx + 1)`, with the comment
     * "Convert 0-based indices to 1-based position IDs". The Subsonic spec itself
     * never states this explicitly; this was verified against the real target
     * server's implementation, not guessed.
     */
    override suspend fun removeSongFromPlaylist(playlistId: String, songIndex: Int) {
        learning(Capability.PLAYLIST_WRITE) { client.call("updatePlaylist.view", listOf("playlistId" to native(playlistId), "songIndexToRemove" to songIndex.toString())) }
    }

    /** Full reorder — see [createPlaylistOrReplace]'s playlistId-replace form. [songIds] is the complete new track order. */
    override suspend fun reorderPlaylist(playlistId: String, name: String, songIds: List<String>) {
        createPlaylistOrReplace(name = name, songIds = songIds, playlistId = playlistId)
    }

    override suspend fun deletePlaylist(id: String) {
        learning(Capability.PLAYLIST_WRITE) { client.call("deletePlaylist.view", listOf("id" to native(id))) }
    }

    override fun streamUrl(songId: String, maxBitRateKbps: Int?): String {
        val params = buildList {
            add("id" to native(songId))
            if (maxBitRateKbps != null) add("maxBitRate" to maxBitRateKbps.toString())
        }
        // No estimateContentLength, on purpose: a transcode then has no length, so the player starts at once but cannot
        // seek until the queue hand-over swaps the song onto its file. With a length declared, the Ogg/Opus extractor
        // seeks to the end for the duration, the server ignores the range, and the whole transcode is read first (about
        // 100 s over a weak cellular link, 2026-09-23).
        return client.endpointUrl("stream.view", params)
    }

    /** Same content as [streamUrl], streamed straight to [destination] through Ktor/CIO — for the http:// download-then-play fallback. See [SubsonicClient.downloadToFile]'s doc for why this streams to a file rather than returning bytes. */
    override suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int?, lease: FetchGate.Lease?) {
        val params = buildList {
            add("id" to native(songId))
            if (maxBitRateKbps != null) add("maxBitRate" to maxBitRateKbps.toString())
        }
        client.downloadToFile("stream.view", destination, params, lease)
    }

    /** Original-file URL — kept for reference/debugging, but DownloadRepository must use [downloadToFile], not fetch this URL directly (see its own comment for why). */
    fun downloadUrl(songId: String): String =
        client.endpointUrl("download.view", listOf("id" to native(songId)))

    /** Streams the original file straight to [destination] through the same Ktor/CIO client as every other call, so it's not subject to Android's cleartext-traffic block. See [SubsonicClient.downloadToFile]'s doc for why this streams rather than returning bytes. */
    override suspend fun downloadToFile(songId: String, destination: File, lease: FetchGate.Lease?) =
        client.downloadToFile("download.view", destination, listOf("id" to native(songId)), lease)

    /**
     * [coverArtId] is any item's `coverArt` field (not the item's own id). The URL keeps the server's own id in
     * `id`, so it stays a valid request, and adds [COVER_ART_SERVER_PARAM] so [AlbumArtRepository], which reads
     * the id and size back out of it, knows which server the art belongs to.
     */
    override fun coverArtUrl(coverArtId: String, size: Int): String =
        client.endpointUrl(
            "getCoverArt.view",
            listOf("id" to native(coverArtId), "size" to size.toString(), COVER_ART_SERVER_PARAM to serverId),
        )

    /** Same content as [coverArtUrl], fetched through Ktor/CIO — for the same reason (cleartext http:// servers). */
    override suspend fun coverArtBytes(coverArtId: String, size: Int): ByteArray =
        client.getBytes("getCoverArt.view", listOf("id" to native(coverArtId), "size" to size.toString()))

    /**
     * OpenSubsonic `getLyricsBySongId` (songLyrics extension) — structured lyrics,
     * timestamped when the server has synced data. Confirmed supported by this
     * project's real Navidrome instance (`getOpenSubsonicExtensions.view` lists
     * `songLyrics` versions [1, 2]) and confirmed the endpoint alone covers all
     * three real states this app needs (synced / plain-text-only / no lyrics) —
     * see SubsonicDtos.kt's SubsonicLyricsList doc. No fallback to the older
     * base-Subsonic `getLyrics.view` (artist+title lookup) is implemented: it's
     * strictly older/narrower than what this returns and wasn't needed against
     * the real server.
     *
     * Returns an empty list, never throws, when a track genuinely has no lyrics —
     * the server responds "ok" with an empty `lyricsList`, not an error.
     */
    override suspend fun getLyrics(songId: String): List<ApiLyricEntry> =
        (learning(Capability.LYRICS) { client.call("getLyricsBySongId.view", listOf("id" to native(songId))) }.lyricsList?.structuredLyrics ?: emptyList())
            .map { it.toApi() }

    /**
     * ArtistDetailScreen's "Similar artists" section — `getArtistInfo2.view`,
     * not `getSimilarSongs2.view`. Despite the name, `getSimilarSongs2`
     * returns *songs* by similar artists, the wrong shape for a list of
     * artists to link into; `getArtistInfo2`'s `similarArtist` field is the
     * one that's actually artist-shaped. See [SubsonicArtistInfo2]'s doc
     * (SubsonicDtos.kt) for the full spec citation this was verified against.
     * [artistId] is the artist's own id, per spec.
     */
    override suspend fun getSimilarArtists(artistId: String, count: Int): List<ApiArtist> =
        client.call(
            "getArtistInfo2.view",
            listOf("id" to native(artistId), "count" to count.toString()),
        ).artistInfo2?.similarArtist.orEmpty().map { it.scoped().toApi() }

    /**
     * ArtistDetailScreen's "Top songs" section — `getTopSongs.view`.
     * [artistName] is the artist's *name*, not id: this is the one Subsonic
     * endpoint keyed by name instead of id (confirmed against the spec, see
     * [SubsonicTopSongs]'s doc) — the OpenSubsonic `topSongsByArtistId`
     * extension would allow an id instead, but isn't assumed supported here.
     */
    /** [artistId] is unused — Subsonic's `getTopSongs` is keyed by name; see the interface doc. */
    override suspend fun getTopSongs(artistId: String, artistName: String, count: Int): List<ApiSong> =
        client.call(
            "getTopSongs.view",
            listOf("artist" to artistName, "count" to count.toString()),
        ).topSongs?.song.orEmpty().map { it.scoped().toApi() }

    /**
     * `submission = false` is a "now playing" notification (fired once a track
     * starts); `true` is the real scrobble (fired once a track has played past
     * the standard threshold — see [PlaybackRepository]'s scrobble watcher for
     * where each is triggered). Navidrome itself just relays this to whatever
     * Last.fm/ListenBrainz account is linked server-side — this app never talks
     * to either directly. [SubsonicClient.call] already throws
     * [SubsonicApiException] on any non-"ok" response, so a server with no
     * linked account — if that's surfaced as a real error rather than a silent
     * no-op, genuinely unconfirmed either way, since this is the one thing
     * only the live server can answer — propagates as a normal exception here,
     * not swallowed.
     */
    suspend fun scrobble(songId: String, submission: Boolean) {
        learning(Capability.SCROBBLE) { client.call("scrobble.view", listOf("id" to native(songId), "submission" to submission.toString())) }
    }

}
