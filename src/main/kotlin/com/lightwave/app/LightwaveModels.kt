package com.lightwave.app

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

enum class RepeatMode { OFF, REPEAT_QUEUE, REPEAT_TRACK }

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
}
