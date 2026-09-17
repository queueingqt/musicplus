package com.lightwave.app.data

/**
 * Endpoint-level Subsonic calls, one function per REST method actually used by
 * Lightwave. Kept separate from [SubsonicClient] so the raw request/auth
 * machinery doesn't get lost in endpoint-specific parameter lists.
 */
class SubsonicApi(private val client: SubsonicClient) {

    val baseUrlIsHttps: Boolean get() = client.baseUrlIsHttps

    suspend fun getArtists(): List<SubsonicArtist> =
        client.call("getArtists.view").artists?.index?.flatMap { it.artist } ?: emptyList()

    suspend fun getArtist(id: String): SubsonicArtistDetail? =
        client.call("getArtist.view", listOf("id" to id)).artist

    /** type: newest | recent | frequent | alphabeticalByName | alphabeticalByArtist | starred */
    suspend fun getAlbumList(type: String, size: Int = 50, offset: Int = 0): List<SubsonicAlbum> =
        client.call(
            "getAlbumList2.view",
            listOf("type" to type, "size" to size.toString(), "offset" to offset.toString()),
        ).albumList2?.album ?: emptyList()

    suspend fun getAlbum(id: String): SubsonicAlbumDetail? =
        client.call("getAlbum.view", listOf("id" to id)).album

    suspend fun search(query: String, artistCount: Int = 20, albumCount: Int = 20, songCount: Int = 30): SubsonicSearchResult =
        client.call(
            "search3.view",
            listOf(
                "query" to query,
                "artistCount" to artistCount.toString(),
                "albumCount" to albumCount.toString(),
                "songCount" to songCount.toString(),
            ),
        ).searchResult3 ?: SubsonicSearchResult()

    suspend fun getStarred(): SubsonicStarred =
        client.call("getStarred2.view").starred2 ?: SubsonicStarred()

    /** [id] may be a song, album, or artist id — Subsonic stars any of the three the same way. */
    suspend fun star(id: String) {
        client.call("star.view", listOf("id" to id))
    }

    suspend fun unstar(id: String) {
        client.call("unstar.view", listOf("id" to id))
    }

    suspend fun getPlaylists(): List<SubsonicPlaylist> =
        client.call("getPlaylists.view").playlists?.playlist ?: emptyList()

    suspend fun getPlaylist(id: String): SubsonicPlaylistDetail? =
        client.call("getPlaylist.view", listOf("id" to id)).playlist

    /**
     * Creates a new playlist ([playlistId] omitted), or replaces an existing one's
     * entire track list when [playlistId] is supplied — confirmed against
     * Navidrome's actual server source (core/playlists/playlists.go, `Create()`):
     * `pls.Tracks = nil` followed by `pls.AddMediaFilesByID(ids)`, i.e. a genuine
     * replace, not a merge/append. That playlistId-replace form is how
     * [reorderPlaylist] rewrites track order below, since the Subsonic API has no
     * dedicated "move" endpoint.
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList(), playlistId: String? = null): SubsonicPlaylistDetail? {
        val params = buildList {
            if (playlistId != null) add("playlistId" to playlistId)
            add("name" to name)
            songIds.forEach { add("songId" to it) }
        }
        return client.call("createPlaylist.view", params).playlist
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        client.call("updatePlaylist.view", listOf("playlistId" to playlistId, "name" to name))
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String) {
        client.call("updatePlaylist.view", listOf("playlistId" to playlistId, "songIdToAdd" to songId))
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
    suspend fun removeSongFromPlaylist(playlistId: String, songIndex: Int) {
        client.call("updatePlaylist.view", listOf("playlistId" to playlistId, "songIndexToRemove" to songIndex.toString()))
    }

    /** Full reorder — see [createPlaylist]'s playlistId-replace form. [songIds] is the complete new track order. */
    suspend fun reorderPlaylist(playlistId: String, name: String, songIds: List<String>) {
        createPlaylist(name = name, songIds = songIds, playlistId = playlistId)
    }

    suspend fun deletePlaylist(id: String) {
        client.call("deletePlaylist.view", listOf("id" to id))
    }

    /** Direct playback URL — hand straight to `LightAudioSource.UrlSource(...)`. Only safe to use when [baseUrlIsHttps] — see PlaybackRepository.toAudioItem. */
    fun streamUrl(songId: String, maxBitRateKbps: Int? = null): String {
        val params = buildList {
            add("id" to songId)
            if (maxBitRateKbps != null) add("maxBitRate" to maxBitRateKbps.toString())
        }
        return client.endpointUrl("stream.view", params)
    }

    /** Same content as [streamUrl], fetched through Ktor/CIO — for the http:// download-then-play fallback. */
    suspend fun streamBytes(songId: String, maxBitRateKbps: Int? = null): ByteArray {
        val params = buildList {
            add("id" to songId)
            if (maxBitRateKbps != null) add("maxBitRate" to maxBitRateKbps.toString())
        }
        return client.getBytes("stream.view", params)
    }

    /** Original-file URL — kept for reference/debugging, but DownloadRepository must use [downloadBytes], not fetch this URL directly (see its own comment for why). */
    fun downloadUrl(songId: String): String =
        client.endpointUrl("download.view", listOf("id" to songId))

    /** Fetches the original file's bytes through the same Ktor/CIO client as every other call, so it's not subject to Android's cleartext-traffic block. */
    suspend fun downloadBytes(songId: String): ByteArray =
        client.getBytes("download.view", listOf("id" to songId))

    /** [id] is any item's `coverArt` field (not the item's own id). */
    fun coverArtUrl(coverArtId: String, size: Int = 300): String =
        client.endpointUrl("getCoverArt.view", listOf("id" to coverArtId, "size" to size.toString()))

    /** Same content as [coverArtUrl], fetched through Ktor/CIO — same shape as [downloadBytes]/[streamBytes], and for the same reason (cleartext http:// servers). */
    suspend fun coverArtBytes(coverArtId: String, size: Int = 300): ByteArray =
        client.getBytes("getCoverArt.view", listOf("id" to coverArtId, "size" to size.toString()))
}
