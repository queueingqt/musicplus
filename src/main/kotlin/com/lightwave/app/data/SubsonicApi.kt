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
}
