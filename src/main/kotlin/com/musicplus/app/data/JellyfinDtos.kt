package com.musicplus.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin REST API (https://api.jellyfin.org) response shapes, as served by a real Jellyfin server — confirmed
 * against this project's own test server, Jellyfin 10.11.0, by pulling its live `/api-docs/openapi.json` rather than
 * assuming a version from external docs. Unlike Subsonic, every endpoint has its own top-level response shape (no
 * shared envelope), and one item DTO ([JellyfinItem]) covers artists, albums and songs alike — Jellyfin tells them
 * apart by [JellyfinItem.type], not by which field of a wrapper is populated.
 */
@Serializable
data class JellyfinAuthResult(
    val User: JellyfinUser? = null,
    val AccessToken: String? = null,
    val ServerId: String? = null,
)

@Serializable
data class JellyfinUser(
    val Id: String,
    val Name: String? = null,
)

/** `GET /Items`, `GET /Search/Hints`'s items are also just [JellyfinItem]s once resolved, `GET /Playlists/{id}/Items`, and the favorites-filtered form of `GET /Items` all return this same shape. */
@Serializable
data class JellyfinItemsResponse(
    val Items: List<JellyfinItem> = emptyList(),
    val TotalRecordCount: Int = 0,
)

@Serializable
data class JellyfinNameId(
    val Name: String? = null,
    val Id: String? = null,
)

@Serializable
data class JellyfinUserData(
    val IsFavorite: Boolean = false,
    val PlaybackPositionTicks: Long = 0,
    val Played: Boolean = false,
)

@Serializable
data class JellyfinMediaSource(
    val Size: Long? = null,
    val Container: String? = null,
)

/**
 * One item — an artist ([Type] `MusicArtist`), an album ([Type] `MusicAlbum`) or a song ([Type] `Audio`), told apart
 * by [Type] since Jellyfin uses one flat item model for all three (and for playlists, [Type] `Playlist`).
 * [RunTimeTicks] is 100ns units — see [ticksToSeconds]. [IndexNumber] is the track number within its album, for a
 * song. [ImageTags] has key `"Primary"` when this item has its own cover art; a song with no key of its own usually
 * still has art via its [AlbumId] — see [JellyfinApi]'s art-resolution doc.
 */
@Serializable
data class JellyfinItem(
    val Id: String,
    val Name: String? = null,
    val Type: String? = null,
    /** The original file's format ("mp3", "flac", ...) — confirmed present at this top level (not only nested under [MediaSources]) on every real item this server returns, default fields, no extra `fields=` param needed. */
    val Container: String? = null,
    val AlbumArtist: String? = null,
    val AlbumArtists: List<JellyfinNameId> = emptyList(),
    val Album: String? = null,
    val AlbumId: String? = null,
    val ArtistItems: List<JellyfinNameId> = emptyList(),
    val RunTimeTicks: Long? = null,
    val IndexNumber: Int? = null,
    val ProductionYear: Int? = null,
    val Genres: List<String> = emptyList(),
    val ImageTags: Map<String, String> = emptyMap(),
    val UserData: JellyfinUserData? = null,
    val MediaSources: List<JellyfinMediaSource> = emptyList(),
    val ChildCount: Int? = null,
    /** A playlist's own track count — `GET /Items` with `includeItemTypes=Playlist` doesn't include [MediaSources], so playlist duration comes from summing its entries instead; see [JellyfinApi.getPlaylist]. */
    val CumulativeRunTimeTicks: Long? = null,
    /** Only present on an entry returned by `GET /Playlists/{id}/Items` — identifies *this playlist entry*, distinct from the song's own [Id] since Jellyfin allows the same song twice in one playlist. See [JellyfinApi.removeSongFromPlaylist]. */
    val PlaylistItemId: String? = null,
) {
    /** 100ns units, Jellyfin's tick convention — see every `RunTimeTicks`/`PlaybackPositionTicks` field. */
    companion object {
        const val TICKS_PER_SECOND = 10_000_000L
        const val TICKS_PER_MS = 10_000L
    }
}

fun Long?.ticksToSeconds(): Int = ((this ?: 0L) / JellyfinItem.TICKS_PER_SECOND).toInt()
fun Long?.ticksToMs(): Long = (this ?: 0L) / JellyfinItem.TICKS_PER_MS
fun Long.msToTicks(): Long = this * JellyfinItem.TICKS_PER_MS

@Serializable
data class JellyfinPlaylistCreationResult(
    val Id: String? = null,
)

/**
 * POST request bodies — each its own `@Serializable` class rather than a raw `mapOf(...)`, because a body with mixed
 * value types (a `String` next to a `Long` next to a `Boolean`) breaks Ktor's kotlinx-serialization content
 * negotiation: it can only "guess" a serializer for a body whose runtime shape is uniform (a plain object, or a
 * collection of one element type), and throws `Serializing collections of different element types is not yet
 * supported` for a heterogeneous `Map<String, Any>` — caught testing playback-progress reporting against the real
 * test server (every `reportPlaybackProgress` call was silently failing; see [JellyfinApi]'s call sites). A
 * same-type map like `mapOf("Name" to name)` happens to work, but these exist for every write body regardless, so
 * nothing here depends on staying accidentally homogeneous.
 */
@Serializable
data class JellyfinCreatePlaylistRequest(
    val Name: String,
    val Ids: List<String> = emptyList(),
    val UserId: String,
)

@Serializable
data class JellyfinRenamePlaylistRequest(val Name: String)

@Serializable
data class JellyfinPlaybackInfoRequest(
    val ItemId: String,
    val PositionTicks: Long,
    val IsPaused: Boolean,
    val CanSeek: Boolean = true,
    val PlaySessionId: String,
)

@Serializable
data class JellyfinLyricResponse(
    val Lyrics: List<JellyfinLyricLine> = emptyList(),
)

@Serializable
data class JellyfinLyricLine(
    val Text: String = "",
    /** 100ns units; absent (not just zero) for an unsynced line. */
    val Start: Long? = null,
)

/** `POST /Users/New` — used only to make the throwaway account this app's own tests log in as; never seen by the real app. */
@Serializable
data class JellyfinCreateUser(val Name: String, val Password: String? = null)

@Serializable
data class JellyfinPublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)
