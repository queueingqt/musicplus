package com.musicplus.app.data

import java.io.File

/** Which wire protocol a saved server speaks — see [ServerProfile.kind]. */
enum class ServerKind {
    SUBSONIC,
    JELLYFIN,
}

/** Extra query parameter a cover-art URL carries, naming which server it's for — see [AlbumArtRepository]. Servers ignore parameters they don't know. */
const val COVER_ART_SERVER_PARAM = "musicplusServer"

/**
 * What any music server backend can do, in the app's own vocabulary rather than either wire protocol's — everything
 * [LibraryRepository], [PlaylistRepository], [AlbumArtRepository], [LyricsRepository], [DownloadRepository],
 * [MediaIntegrity] and [PlaybackRepository] need from "a server", with [SubsonicApi] and [JellyfinApi] as the two
 * implementations. **Every implementation speaks the app's scoped ids, and the wire speaks the server's own** (see
 * [ServerScope]) — everything returned here already has its ids scoped to [serverId], exactly as before this
 * interface existed.
 *
 * Deliberately NOT on this interface: [SubsonicApi.scrobble] and Jellyfin's own playback-progress reporting
 * ([JellyfinApi.reportPlaybackStart]/[JellyfinApi.reportPlaybackProgress]/[JellyfinApi.reportPlaybackStopped]).
 * They look like the same idea ("tell the server about a play") but are not: Subsonic's scrobble is a single opt-in
 * action gated by [AppSettingsRepository.scrobblingEnabled], purely to relay to whatever Last.fm/ListenBrainz account
 * is linked server-side. Jellyfin's playback-progress calls are unconditional core Jellyfin behavior (they drive its
 * own resume-position and play history) and have nothing to do with scrobbling — forcing them into one shared method
 * would either wrongly gate Jellyfin's resume tracking behind a "scrobbling" toggle, or wrongly make Subsonic's opt-in
 * relay unconditional. [PlaybackRepository] calls each backend's own method directly instead.
 */
interface MusicApi {
    val serverId: String

    /** A server reachable over https:// — its songs go straight to the player and are never kept in the stream cache; see [PlaybackRepository.toAudioItem]. */
    val baseUrlIsHttps: Boolean

    /** Whether the player itself can fetch [streamUrl] (https://, or http:// on a build that permits cleartext) — see [playerCanFetch]. An http:// server that can be fetched streams the song being started but keeps its stream cache and prefetch. */
    val playerCanFetchDirectly: Boolean

    /** An ordinary, signed-in question — the control that says a "no" from a capability probe means something. */
    suspend fun checkLogin(): Result<Unit>

    /** A cheap "is it there" request; the outcome reaches [ServerReachability] through the underlying client. */
    suspend fun ping(): Result<Unit>

    suspend fun getArtists(): List<ApiArtist>
    suspend fun getArtist(id: String): ApiArtistDetail?

    /** The *entire* library, alphabetical by name, one page at a time — the only ordering any caller has ever needed. */
    suspend fun getAlbumList(size: Int, offset: Int): List<ApiAlbum>
    suspend fun getAlbum(id: String): ApiAlbumDetail?

    /** One song by id — used only to compare a downloaded file's length against the server's; see [MediaIntegrity]. */
    suspend fun getSong(id: String): ApiSong?

    suspend fun search(query: String, artistCount: Int = 20, albumCount: Int = 20, songCount: Int = 30): ApiSearchResult

    /** One page of the *entire* song library, for the flat "Songs" browse list — see [LibraryRepository.refreshAllSongs]. */
    suspend fun getSongsPage(songCount: Int, songOffset: Int): List<ApiSong>

    suspend fun getStarred(): ApiStarred

    /** [id] may be a song, album, or artist id. */
    suspend fun star(id: String)
    suspend fun unstar(id: String)

    suspend fun getPlaylists(): List<ApiPlaylist>
    suspend fun getPlaylist(id: String): ApiPlaylistDetail?
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): ApiPlaylistDetail?
    suspend fun renamePlaylist(playlistId: String, name: String)
    suspend fun addSongToPlaylist(playlistId: String, songId: String)

    /** [songIndex] is the song's zero-based position in the playlist's *current* track list, as last read from [getPlaylist]. */
    suspend fun removeSongFromPlaylist(playlistId: String, songIndex: Int)

    /** Full reorder: [songIds] is the complete new track order. */
    suspend fun reorderPlaylist(playlistId: String, name: String, songIds: List<String>)
    suspend fun deletePlaylist(id: String)

    /** Direct playback URL, fully authenticated — only safe to feed straight to the player when [playerCanFetchDirectly]; see [PlaybackRepository.toAudioItem]. */
    fun streamUrl(songId: String, maxBitRateKbps: Int? = null): String

    /** Same content as [streamUrl] (transcoded to [maxBitRateKbps] when given), streamed to [destination] through the app's own HTTP client — the http:// download-then-play fallback, and the capped-quality download path. */
    suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int? = null, lease: FetchGate.Lease? = null)

    /** The original file, streamed to [destination] through the app's own HTTP client — never transcoded. */
    suspend fun downloadToFile(songId: String, destination: File, lease: FetchGate.Lease? = null)

    /** [coverArtId] is any item's own cover-art reference (not necessarily the item's own id) — see [AlbumArtRepository]. */
    fun coverArtUrl(coverArtId: String, size: Int = 300): String
    suspend fun coverArtBytes(coverArtId: String, size: Int = 300): ByteArray

    /** Empty when the track genuinely has no lyrics — never throws for that case, only for a real request failure. */
    suspend fun getLyrics(songId: String): List<ApiLyricEntry>

    suspend fun getSimilarArtists(artistId: String, count: Int = 20): List<ApiArtist>

    /** [artistId] and [artistName] are both given since Subsonic's `getTopSongs` is keyed by name and Jellyfin's equivalent by id. */
    suspend fun getTopSongs(artistId: String, artistName: String, count: Int = 20): List<ApiSong>
}

// --- Shared, already-scoped DTOs — the common shape both backends map their own wire format into. ---

data class ApiArtist(
    val id: String,
    val name: String,
    val coverArtId: String?,
    val albumCount: Int,
    val starred: Boolean,
)

data class ApiArtistDetail(
    val id: String,
    val name: String,
    val coverArtId: String?,
    val albumCount: Int,
    val starred: Boolean,
    val albums: List<ApiAlbum>,
)

data class ApiAlbum(
    val id: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val coverArtId: String?,
    val songCount: Int,
    val durationSec: Int,
    val year: Int?,
    val genre: String?,
    val starred: Boolean,
)

data class ApiAlbumDetail(
    val id: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val coverArtId: String?,
    val songCount: Int,
    val durationSec: Int,
    val year: Int?,
    val genre: String?,
    val starred: Boolean,
    val songs: List<ApiSong>,
)

data class ApiSong(
    val id: String,
    val title: String,
    val album: String?,
    val albumId: String?,
    val artist: String?,
    val artistId: String?,
    val trackNumber: Int?,
    val durationSec: Int,
    val coverArtId: String?,
    val suffix: String?,
    /** Length in bytes of the original file — see [MediaIntegrity]. */
    val sizeBytes: Long?,
    val starred: Boolean,
)

data class ApiSearchResult(
    val artists: List<ApiArtist> = emptyList(),
    val albums: List<ApiAlbum> = emptyList(),
    val songs: List<ApiSong> = emptyList(),
)

data class ApiStarred(
    val artists: List<ApiArtist> = emptyList(),
    val albums: List<ApiAlbum> = emptyList(),
    val songs: List<ApiSong> = emptyList(),
)

data class ApiPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
    val durationSec: Int,
    val coverArtId: String?,
)

data class ApiPlaylistDetail(
    val id: String,
    val name: String,
    val songCount: Int,
    val durationSec: Int,
    val coverArtId: String?,
    val entries: List<ApiSong>,
)

/** One alternative set of lyrics for a track (a server may offer more than one — e.g. a translation alongside the original). */
data class ApiLyricEntry(
    val kind: String?,
    val synced: Boolean,
    val lines: List<ApiLyricLine>,
)

data class ApiLyricLine(
    /** Milliseconds from track start. Only present when the parent entry is [ApiLyricEntry.synced]. */
    val startMs: Long?,
    val text: String,
)
