package com.musicplus.app.data

import java.io.File

/**
 * A [MusicApi] that does nothing it was not told to: every endpoint fails loudly, so a test only stubs what it exercises and a code path
 * that reaches for something else says so. Both real adapters and this one are behind the same seam.
 */
open class StubMusicApi(override val serverId: String = "srv") : MusicApi {
    private fun unstubbed(what: String): Nothing = throw UnsupportedOperationException("StubMusicApi.$what is not stubbed")

    override suspend fun probeCapabilities(): Map<Capability, Support>? = unstubbed("probeCapabilities")
    override suspend fun checkLogin(): Result<Unit> = unstubbed("checkLogin")
    override suspend fun ping(): Result<Unit> = unstubbed("ping")
    override suspend fun getArtists(): List<ApiArtist> = unstubbed("getArtists")
    override suspend fun getArtist(id: String): ApiArtistDetail? = unstubbed("getArtist")
    override suspend fun getAlbumList(size: Int, offset: Int): List<ApiAlbum> = unstubbed("getAlbumList")
    override suspend fun getAlbum(id: String): ApiAlbumDetail? = unstubbed("getAlbum")
    override suspend fun getSong(id: String): ApiSong? = unstubbed("getSong")
    override suspend fun search(query: String, artistCount: Int, albumCount: Int, songCount: Int): ApiSearchResult = unstubbed("search")
    override suspend fun getSongsPage(songCount: Int, songOffset: Int): List<ApiSong> = unstubbed("getSongsPage")
    override suspend fun getStarred(): ApiStarred = unstubbed("getStarred")
    override suspend fun star(id: String) = unstubbed("star")
    override suspend fun unstar(id: String) = unstubbed("unstar")
    override suspend fun getPlaylists(): List<ApiPlaylist> = unstubbed("getPlaylists")
    override suspend fun getPlaylist(id: String): ApiPlaylistDetail? = unstubbed("getPlaylist")
    override suspend fun createPlaylist(name: String, songIds: List<String>): ApiPlaylistDetail? = unstubbed("createPlaylist")
    override suspend fun renamePlaylist(playlistId: String, name: String) = unstubbed("renamePlaylist")
    override suspend fun addSongToPlaylist(playlistId: String, songId: String) = unstubbed("addSongToPlaylist")
    override suspend fun removeSongFromPlaylist(playlistId: String, songIndex: Int) = unstubbed("removeSongFromPlaylist")
    override suspend fun reorderPlaylist(playlistId: String, name: String, songIds: List<String>) = unstubbed("reorderPlaylist")
    override suspend fun deletePlaylist(id: String) = unstubbed("deletePlaylist")
    override fun streamUrl(songId: String, maxBitRateKbps: Int?): String = unstubbed("streamUrl")
    override suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int?, lease: FetchGate.Lease?) = unstubbed("streamToFile")
    override suspend fun downloadToFile(songId: String, destination: File, lease: FetchGate.Lease?) = unstubbed("downloadToFile")
    override suspend fun coverArtBytes(coverArtId: String, size: Int): ByteArray = unstubbed("coverArtBytes")
    override suspend fun getLyrics(songId: String): List<ApiLyricEntry> = unstubbed("getLyrics")
    override suspend fun getSimilarArtists(artistId: String, count: Int): List<ApiArtist> = unstubbed("getSimilarArtists")
    override suspend fun getTopSongs(artistId: String, artistName: String, count: Int): List<ApiSong> = unstubbed("getTopSongs")
}

/** An [ApiLookup] over a few fake servers, routing by the server an id is scoped to as the real one does. */
class FakeApiLookup(private val apis: Map<String, MusicApi>, private val active: String? = apis.keys.firstOrNull()) : ApiLookup {
    override suspend fun get(): MusicApi? = active?.let(apis::get)
    override suspend fun forId(id: String): MusicApi? = ServerScope.serverOf(id)?.let(apis::get) ?: get()
    override suspend fun forServer(serverId: String): MusicApi? = apis[serverId]
    override fun peek(): MusicApi? = active?.let(apis::get)
    override fun peekFor(id: String): MusicApi? = (ServerScope.serverOf(id) ?: active)?.let(apis::get)
}
