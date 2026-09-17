package com.lightwave.app.data

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
    val searchResult3: SubsonicSearchResult? = null,
    val starred2: SubsonicStarred? = null,
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
