package com.musicplus.app.data

import java.io.File

/**
 * Endpoint-level Subsonic calls, one function per REST method actually used by
 * Music +. Kept separate from [SubsonicClient] so the raw request/auth
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
    suspend fun getSongsPage(songCount: Int, songOffset: Int): List<SubsonicSong> =
        client.call(
            "search3.view",
            listOf(
                "query" to "",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to songCount.toString(),
                "songOffset" to songOffset.toString(),
            ),
        ).searchResult3?.song ?: emptyList()

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

    /** Same content as [streamUrl], streamed straight to [destination] through Ktor/CIO — for the http:// download-then-play fallback. See [SubsonicClient.downloadToFile]'s doc for why this streams to a file rather than returning bytes. */
    suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int? = null) {
        val params = buildList {
            add("id" to songId)
            if (maxBitRateKbps != null) add("maxBitRate" to maxBitRateKbps.toString())
        }
        client.downloadToFile("stream.view", destination, params)
    }

    /** Original-file URL — kept for reference/debugging, but DownloadRepository must use [downloadToFile], not fetch this URL directly (see its own comment for why). */
    fun downloadUrl(songId: String): String =
        client.endpointUrl("download.view", listOf("id" to songId))

    /** Streams the original file straight to [destination] through the same Ktor/CIO client as every other call, so it's not subject to Android's cleartext-traffic block. See [SubsonicClient.downloadToFile]'s doc for why this streams rather than returning bytes. */
    suspend fun downloadToFile(songId: String, destination: File) =
        client.downloadToFile("download.view", destination, listOf("id" to songId))

    /** [id] is any item's `coverArt` field (not the item's own id). */
    fun coverArtUrl(coverArtId: String, size: Int = 300): String =
        client.endpointUrl("getCoverArt.view", listOf("id" to coverArtId, "size" to size.toString()))

    /** Same content as [coverArtUrl], fetched through Ktor/CIO — same shape as [downloadBytes]/[streamBytes], and for the same reason (cleartext http:// servers). */
    suspend fun coverArtBytes(coverArtId: String, size: Int = 300): ByteArray =
        client.getBytes("getCoverArt.view", listOf("id" to coverArtId, "size" to size.toString()))

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
    suspend fun getLyricsBySongId(songId: String): List<SubsonicStructuredLyrics> =
        client.call("getLyricsBySongId.view", listOf("id" to songId)).lyricsList?.structuredLyrics ?: emptyList()

    /**
     * ArtistDetailScreen's "Similar artists" section — `getArtistInfo2.view`,
     * not `getSimilarSongs2.view`. Despite the name, `getSimilarSongs2`
     * returns *songs* by similar artists, the wrong shape for a list of
     * artists to link into; `getArtistInfo2`'s `similarArtist` field is the
     * one that's actually artist-shaped. See [SubsonicArtistInfo2]'s doc
     * (SubsonicDtos.kt) for the full spec citation this was verified against.
     * [artistId] is the artist's own id, per spec.
     */
    suspend fun getSimilarArtists(artistId: String, count: Int = 20): List<SubsonicArtist> =
        client.call(
            "getArtistInfo2.view",
            listOf("id" to artistId, "count" to count.toString()),
        ).artistInfo2?.similarArtist ?: emptyList()

    /**
     * ArtistDetailScreen's "Top songs" section — `getTopSongs.view`.
     * [artistName] is the artist's *name*, not id: this is the one Subsonic
     * endpoint keyed by name instead of id (confirmed against the spec, see
     * [SubsonicTopSongs]'s doc) — the OpenSubsonic `topSongsByArtistId`
     * extension would allow an id instead, but isn't assumed supported here.
     */
    suspend fun getTopSongs(artistName: String, count: Int = 20): List<SubsonicSong> =
        client.call(
            "getTopSongs.view",
            listOf("artist" to artistName, "count" to count.toString()),
        ).topSongs?.song ?: emptyList()

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
        client.call("scrobble.view", listOf("id" to songId, "submission" to submission.toString()))
    }
}
