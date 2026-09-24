package com.musicplus.app

import com.musicplus.app.data.DownloadStatus

/** UI-facing domain models — separate from the Subsonic DTOs and Room entities they're built from. */

data class Artist(
    val id: String,
    val name: String,
    val coverArtUrl: String?,
    val albumCount: Int,
    val isFavorite: Boolean,    /** The server's name, set only where it tells this row apart from the same one on another server (always for a playlist). See [com.musicplus.app.data.ServerLabels]. */
    val serverLabel: String? = null,
)

data class Album(
    val id: String,
    val name: String,
    val artistId: String?,
    val artistName: String?,
    val coverArtUrl: String?,
    val songCount: Int,
    val durationSec: Int,
    val year: Int?,
    val isFavorite: Boolean,    /** The server's name, set only where it tells this row apart from the same one on another server (always for a playlist). See [com.musicplus.app.data.ServerLabels]. */
    val serverLabel: String? = null,
)

data class Track(
    val id: String,
    val title: String,
    val albumId: String?,
    val albumName: String?,
    val artistId: String?,
    val artistName: String?,
    val trackNumber: Int?,
    val durationSec: Int,
    val coverArtUrl: String?,
    val isFavorite: Boolean,
    /**
     * Live — reflects [com.musicplus.app.data.DownloadRepository]'s own
     * state, not just a completion flag; null means never queued/downloaded.
     * [LibraryRepository]/[PlaylistRepository] join against the download
     * table to populate this (see [com.musicplus.app.data.TrackMapping.kt]);
     * previously this was always false coming out of those two repositories
     * — every screen that needed real per-track download status had to
     * separately re-derive it, and downloaded tracks played from any
     * non-download-originated screen silently streamed over the network
     * instead of using the local file, since [localFilePath] was always
     * null too. Confirmed live, 2026-09-18 architecture review + this
     * session's own /grilling pass.
     */
    val downloadStatus: DownloadStatus?,
    /** Set once [downloadStatus] is COMPLETE — playback should prefer this over streaming when present. */
    val localFilePath: String?,    /** The server's name, set only where it tells this row apart from the same one on another server (always for a playlist). See [com.musicplus.app.data.ServerLabels]. */
    val serverLabel: String? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int,
    val durationSec: Int,    /** The server's name, set only where it tells this row apart from the same one on another server (always for a playlist). See [com.musicplus.app.data.ServerLabels]. */
    val serverLabel: String? = null,
)

enum class RepeatMode { OFF, REPEAT_QUEUE, REPEAT_TRACK }

/**
 * A capped bitrate (kbps) to request from the server instead of always
 * fetching the original file — see
 * [com.musicplus.app.data.AppSettingsRepository]'s
 * streamQualityWifi/streamQualityCellular/downloadQuality and
 * [com.musicplus.app.data.PlaybackRepository]'s doc for why this exists
 * (issue #7: some lossless-source tracks are hundreds of MB, turning even a
 * single track's resolve into a real multi-second block on a cleartext
 * server). Passed straight through as Subsonic's `stream.view` `maxBitRate`
 * parameter, which accepts any integer the server's transcoder supports —
 * not a fixed set of tiers, so this is a plain `Int?` rather than a closed
 * enum. `null` omits the parameter entirely, which the server treats as
 * "no limit" (the original file).
 *
 * [STREAM_QUALITY_PRESETS] are just common, recognizable tiers to show as
 * quick picks in the UI (roughly matching typical streaming-service
 * quality levels) — not something the API itself restricts you to. Any
 * other value (e.g. 96, or 1411 for literal CD quality) is equally valid
 * and reachable via the UI's "Custom…" entry. A plain `Int?` (kbps, null
 * meaning "original") is used everywhere this value flows, rather than a
 * closed enum, for the same reason.
 */
val STREAM_QUALITY_PRESETS: List<Int> = listOf(96, 128, 192, 320)

fun streamQualityLabel(maxBitRateKbps: Int?): String = maxBitRateKbps?.let { "$it kbps" } ?: "Original"

/**
 * What actually happened when a repository attempted a server write.
 * [NOT_CONFIGURED] (no server saved at all) is deliberately distinct from
 * [FAILED] (a configured server's call itself threw, e.g. offline or a
 * transient error) — only [FAILED] is worth queueing for a later retry;
 * there's nothing a background retry could do about there being no server to
 * reach at all. See [com.musicplus.app.data.SyncQueueRepository].
 */
enum class WriteOutcome { SUCCESS, NOT_CONFIGURED, FAILED }

data class PlaybackState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Surfaced from LightAudioPlayer.error — was previously dropped entirely, making a real playback failure indistinguishable from "still loading" in the UI. */
    val errorMessage: String? = null,
    /**
     * True for the brief window between [currentTrack] switching to a new
     * track and playback actually being ready — `currentTrack` itself already
     * resolves synchronously in that window (see PlaybackRepository's
     * pendingIndex), but [isPlaying]/[positionMs]/[durationMs] are all zeroed
     * out rather than showing the *previous* track's still-live values, so
     * this is the one signal that tells a real "loading" state apart from a
     * genuinely paused one. Reported live: tapping a track showed a plain
     * play icon (identical to paused) during this window, reading as "the
     * tap didn't do anything."
     */
    val isLoading: Boolean = false,
    /**
     * False while the current song is playing from a transcoded stream: it has no length until it has been read to the end, so the
     * player cannot seek in it (skip back/forward do nothing). The song being started is swapped onto its file soon after it
     * starts, which makes this true again (see PlaybackRepository.extendToFullQueue); a song reached later as a stream, beyond what
     * is fetched ahead off Wi-Fi, stays a stream for as long as it plays. The screen dims the seek controls meanwhile rather than
     * leave buttons that silently do nothing, and says only what is true in both cases: unavailable while the song streams.
     */
    val canSeek: Boolean = true,
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)

    /** Tracks after [currentTrack] in [queue] — what the queue view shows as "up next". */
    val upcomingTracks: List<Track> get() = if (currentIndex in queue.indices) queue.drop(currentIndex + 1) else emptyList()
}

/** One lyric line. [startMs] is null for unsynced (plain-text-only) lyrics. */
data class LyricLine(val startMs: Long?, val text: String)

/**
 * Now Playing's lyrics pane state for the current track — fetched fresh per
 * track (not cached in Room; lyrics are cheap to refetch and don't need to
 * survive offline like the library does). See PlayerScreenViewModel.
 */
sealed class LyricsState {
    data object Loading : LyricsState()
    /** The server genuinely has no lyrics for this track (confirmed "ok" + empty response, not an error). */
    data object NoLyrics : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
    data class Plain(val text: String) : LyricsState()
    data class Error(val message: String) : LyricsState()
}

private fun String?.withServer(label: String?) = this.orEmpty() + (label?.let { " · $it" } ?: "")

/** The artist under an album's name: "Artist", or "Artist · Bandcamp" where the same album is also on another server. */
val Album.artistLine: String get() = (artistName ?: "Unknown artist").withServer(serverLabel)

/**
 * The artist beside a song's title. The server comes *first* here ("NASTY copy · Artist"), unlike [Album.artistLine]: a
 * song row is one line with the title taking most of it, so a long artist name is what gets cut, and the label has to
 * survive that or two copies of a song would read exactly alike.
 */
val Track.artistLine: String
    get() = (artistName ?: "Unknown artist").let { artist -> serverLabel?.let { "$it · $artist" } ?: artist }

/** An artist's second line: "12 albums", or "12 albums · Bandcamp" where the same artist is also on another server. */
val Artist.albumsLine: String get() = "$albumCount albums".withServer(serverLabel)

/** A playlist's second line: "58 tracks · NASTY". Every playlist shows where it lives. */
val Playlist.detailLine: String get() = "$songCount tracks".withServer(serverLabel)

/** An artist's name where the row has no second line (Favorites, Search): "Name", or "Name · Bandcamp" where it is also on another server. */
val Artist.nameLine: String get() = name.withServer(serverLabel)

/** An album's name where the row has no second line (Favorites, Search). */
val Album.nameLine: String get() = name.withServer(serverLabel)
