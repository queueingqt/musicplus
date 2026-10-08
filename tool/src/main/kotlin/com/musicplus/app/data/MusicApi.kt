package com.musicplus.app.data

import com.musicplus.app.data.playback.SongStreams
import java.io.File

/** Which wire protocol a saved server speaks — see [ServerProfile.kind]. */
enum class ServerKind {
    SUBSONIC,
    JELLYFIN,
}

/** What a probe found out. UNKNOWN means it could not tell (the server was unreachable, or answered oddly), so nothing is concluded. */
enum class Support { YES, NO, UNKNOWN }

/**
 * A server that counts a listen as a Last.fm-style relay (Subsonic's `scrobble`): "now playing" when a listen starts, the real scrobble
 * once it counts. Opt-in (see `AppSettingsRepository.scrobblingEnabled`), purely to relay to whatever account is linked server-side.
 */
interface ScrobbleTarget {
    /** [songId] is scoped. [submission] false is "now playing", true the real scrobble. Throws on a failure, for the caller to surface. */
    suspend fun scrobble(songId: String, submission: Boolean)
}

/**
 * A server that keeps a resume position and a play history from what it is told about playback (Jellyfin's own reporting).
 * Unconditional, never gated by the scrobbling setting: it drives that server's own resume position and history and has nothing to do
 * with a Last.fm relay. The two look like the same idea ("tell the server about a play") but are not, which is why they are two
 * interfaces and not one method: forcing them together would either wrongly gate Jellyfin's resume tracking behind a "scrobbling"
 * toggle, or wrongly make Subsonic's opt-in relay unconditional.
 */
interface PlayReporter {
    /** Once, when a song starts (or resumes into) playing. [songId] is scoped. */
    suspend fun reportPlaybackStart(songId: String, positionMs: Long, playSessionId: String)

    /** Periodically while a song remains current (playing or paused): this is what actually saves the resume position server-side. */
    suspend fun reportPlaybackProgress(songId: String, positionMs: Long, isPaused: Boolean, playSessionId: String)

    /** Once, when a song stops being current. */
    suspend fun reportPlaybackStopped(songId: String, positionMs: Long, playSessionId: String)
}

/**
 * What any music server backend can do, in the app's own vocabulary rather than either wire protocol's — everything
 * [LibraryRepository], [PlaylistRepository], [AlbumArtRepository], [LyricsRepository], [DownloadRepository],
 * [MediaIntegrity] and [PlaybackRepository] need from "a server", with [SubsonicApi] and [JellyfinApi] as the two
 * implementations. **Every implementation speaks the app's scoped ids, and the wire speaks the server's own** (see
 * [ServerScope]) — everything returned here already has its ids scoped to [serverId], exactly as before this
 * interface existed.
 *
 * What only some backends can do is offered as an optional capability ([scrobbler], [playReporter]) rather than as a method every
 * backend must have, and rather than something callers reach by casting to a backend: a caller asks the api what it offers and
 * uses that, so a fake api can offer anything.
 */
interface MusicApi : SongStreams, CoverArtSource {
    val serverId: String

    /** What this server takes for counting a listen as a relay, or null when it takes none (Jellyfin has no equivalent of a `scrobble.view` relay). */
    val scrobbler: ScrobbleTarget? get() = null

    /** What this server takes for keeping a resume position and play history, or null when it takes none. */
    val playReporter: PlayReporter? get() = null

    /**
     * Finds out what this server can do, or null when it could not be asked. A backend that must be asked does so with harmless requests
     * (Subsonic's vocabulary is `.view` endpoints, and not every compatible server, Bandcamp's for one, offers all of them), and only
     * after an ordinary request has been answered, so a server that is merely down or slow is never written off. A backend whose
     * feature set is documented by its own API answers without asking.
     */
    suspend fun probeCapabilities(): Map<Capability, Support>?

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

    // streamUrl and streamToFile come from SongStreams: what TrackSources needs to decide between streaming a song and fetching its file.

    /** The original file, streamed to [destination] through the app's own HTTP client — never transcoded. */
    suspend fun downloadToFile(songId: String, destination: File, lease: FetchGate.Lease? = null)

    // coverArtBytes comes from CoverArtSource: a scoped cover-art id and a size in, the picture's bytes out; how the adapter asks its
    // server is its own business (there is no URL for the rest of the app to build or parse).

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
