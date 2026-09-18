package com.musicplus.app

/** UI-facing domain models — separate from the Subsonic DTOs and Room entities they're built from. */

data class Artist(
    val id: String,
    val name: String,
    val coverArtUrl: String?,
    val albumCount: Int,
    val isFavorite: Boolean,
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
    val isFavorite: Boolean,
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
    val isDownloaded: Boolean,
    /** Set once downloaded — playback should prefer this over streaming when present. */
    val localFilePath: String?,
)

data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int,
    val durationSec: Int,
)

enum class RepeatMode { OFF, REPEAT_QUEUE, REPEAT_TRACK }

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
