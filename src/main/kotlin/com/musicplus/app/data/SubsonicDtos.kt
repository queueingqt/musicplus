package com.musicplus.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subsonic API (https://www.subsonic.org/pages/api.jsp) response envelope, as served
 * by Navidrome and any other Subsonic-compatible server (Gonic, Airsonic, ...).
 * Every endpoint response is wrapped in "subsonic-response"; only the field(s)
 * relevant to the call made are populated.
 */
@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicResponse(
    val status: String, // "ok" | "failed"
    val version: String,
    val error: SubsonicError? = null,
    val artists: SubsonicArtistsIndex? = null,
    val artist: SubsonicArtistDetail? = null,
    val albumList2: SubsonicAlbumList? = null,
    val album: SubsonicAlbumDetail? = null,
    // `getSong` — one song by id; used to compare a downloaded file's length with the server's.
    val song: SubsonicSong? = null,
    val searchResult3: SubsonicSearchResult? = null,
    val starred2: SubsonicStarred? = null,
    val playlists: SubsonicPlaylists? = null,
    val playlist: SubsonicPlaylistDetail? = null,
    // "Similar artists" section (ArtistDetailScreen) — see [SubsonicArtistInfo2]'s
    // own doc for why this is `getArtistInfo2`, not `getSimilarSongs2`.
    val artistInfo2: SubsonicArtistInfo2? = null,
    // "Top songs" section (ArtistDetailScreen) — see [SubsonicTopSongs]'s doc.
    val topSongs: SubsonicTopSongs? = null,
    // OpenSubsonic `getLyricsBySongId` (songLyrics extension) — absent entirely
    // (not merely an empty list) when the server has no lyrics data at all for a
    // track; confirmed directly against this project's real Navidrome instance
    // (2026-09-17), not assumed from the spec doc alone.
    val lyricsList: SubsonicLyricsList? = null,
) {
    val isOk: Boolean get() = status == "ok"
}

@Serializable
data class SubsonicError(
    val code: Int,
    val message: String,
)

@Serializable
data class SubsonicArtistsIndex(
    val index: List<SubsonicArtistIndexGroup> = emptyList(),
)

@Serializable
data class SubsonicArtistIndexGroup(
    val name: String,
    val artist: List<SubsonicArtist> = emptyList(),
)

@Serializable
data class SubsonicArtist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
)

@Serializable
data class SubsonicArtistDetail(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val album: List<SubsonicAlbum> = emptyList(),
)

@Serializable
data class SubsonicAlbumList(
    val album: List<SubsonicAlbum> = emptyList(),
)

@Serializable
data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
)

@Serializable
data class SubsonicAlbumDetail(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val song: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicSong(
    val id: String,
    val title: String,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val duration: Int = 0,
    val coverArt: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    // Length in bytes of the original file — see MediaIntegrity.
    val size: Long? = null,
    val starred: String? = null,
)

@Serializable
data class SubsonicSearchResult(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicStarred(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicPlaylists(
    val playlist: List<SubsonicPlaylist> = emptyList(),
)

@Serializable
data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
)

/** Same shape as [SubsonicPlaylist] plus its track list — `getPlaylist`'s response.
 * Confirmed against the 1.16.1 XSD (`PlaylistWithSongs`): the track-list element is
 * named "entry" (type `Child`, same shape as `getAlbum`'s "song" list), not "song". */
@Serializable
data class SubsonicPlaylistDetail(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<SubsonicSong> = emptyList(),
)

/**
 * `getArtistInfo2` response — the endpoint actually used for ArtistDetailScreen's
 * "Similar artists" section, NOT `getSimilarSongs2` despite the more
 * artist-sounding name of that other endpoint. Verified against the real
 * OpenSubsonic spec docs (not guessed from the endpoint name alone), fetched
 * 2026-09-18:
 *  - https://opensubsonic.netlify.app/docs/endpoints/getsimilarsongs2/ — its
 *    response (`similarSongs2`) holds a `song` list: "Returns a random
 *    collection of songs from the given artist and similar artists." A *song*
 *    list, not an artist list — the wrong shape for this section regardless
 *    of the name.
 *  - https://opensubsonic.netlify.app/docs/endpoints/getartistinfo2/ +
 *    https://opensubsonic.netlify.app/docs/responses/artistinfo2/ —
 *    `getArtistInfo2.view` (param `id` = the artist's own id, `count` = max
 *    similar artists) returns `artistInfo2.similarArtist`, documented as
 *    "Array of ArtistID3".
 *  - https://opensubsonic.netlify.app/docs/responses/artistid3/ — ArtistID3's
 *    fields (id, name, coverArt, albumCount, starred, plus some
 *    OpenSubsonic-only extras this app doesn't use) are exactly the shape
 *    [SubsonicArtist] already models, so `similarArtist` deserializes
 *    straight into `List<SubsonicArtist>` with no new DTO needed for the
 *    entries themselves — only this wrapper.
 *
 * `biography`/image-URL fields `getArtistInfo2` also returns aren't modeled
 * here — unused by this app's "Similar artists" section, which only needs
 * the artist list itself.
 */
@Serializable
data class SubsonicArtistInfo2(
    val similarArtist: List<SubsonicArtist> = emptyList(),
)

/**
 * `getTopSongs` response wrapper — same flat single-list shape convention as
 * [SubsonicAlbumList]/[SubsonicPlaylists]. Request param is `artist` (the
 * artist's *name*, not id — see [SubsonicApi.getTopSongs]'s doc), confirmed
 * https://opensubsonic.netlify.app/docs/endpoints/gettopsongs/, fetched
 * 2026-09-18.
 */
@Serializable
data class SubsonicTopSongs(
    val song: List<SubsonicSong> = emptyList(),
)

/**
 * OpenSubsonic `getLyricsBySongId` response (songLyrics extension v1/v2 —
 * https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/). Verified
 * directly against this project's real Navidrome 0.63.2 instance, not just the
 * spec doc: `getOpenSubsonicExtensions.view` lists `songLyrics` versions [1, 2],
 * and real probes against `/rest/getLyricsBySongId.view` confirmed all three
 * shapes this app needs to handle —
 *  - synced: `structuredLyrics: [{ synced: true, line: [{start, value}, ...] }]`
 *  - unsynced-only: `structuredLyrics: [{ synced: false, line: [{value}, ...] }]`
 *    (lines have no `start` in this case — seen for real on an instrumental
 *    track, whose single line was literally `{"value":"Instrumental"}`)
 *  - no lyrics at all: `lyricsList: {}` — `structuredLyrics` is missing
 *    entirely (status is still "ok", not an error), hence the `= emptyList()` default.
 */
@Serializable
data class SubsonicLyricsList(
    val structuredLyrics: List<SubsonicStructuredLyrics> = emptyList(),
)

@Serializable
data class SubsonicStructuredLyrics(
    /** "main" | "translation" | "pronunciation" per spec — not observed set on this server's real responses, kept optional. */
    val kind: String? = null,
    val lang: String? = null,
    val synced: Boolean = false,
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    val line: List<SubsonicLyricLine> = emptyList(),
)

@Serializable
data class SubsonicLyricLine(
    /** Milliseconds from track start. Only present when the parent entry is `synced`. */
    val start: Long? = null,
    val value: String = "",
)
