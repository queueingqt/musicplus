package com.musicplus.app.data

import com.musicplus.app.Track

/**
 * Shared ApiSong -> TrackEntity -> Track mapping. [LibraryRepository]
 * and [PlaylistRepository] both independently hand-rolled an identical copy
 * of this pair (the latter's own doc comment even called out the
 * duplication as a deliberate choice) — confirmed live, 2026-09-18
 * architecture review. `apiHolder` is a parameter rather than an implicit
 * receiver field (the shape both call sites used before) since this is a
 * top-level function shared across two repository classes, each with their
 * own `apiHolder` instance.
 */
fun ApiSong.toTrackEntity() =
    TrackEntity(id, title, albumId, album, artistId, artist, trackNumber, durationSec, coverArtId, suffix, starred)

/**
 * [download] is this track's own row from [DownloadRepository.observeAll]
 * (or null if it's never been queued/downloaded) — callers join against the
 * download table once per list, not once per track; see LibraryRepository/
 * PlaylistRepository's observe* functions for the actual combine().
 * [Track.localFilePath] only ever comes from [download] (never guessed or
 * reconstructed), and only when its status is COMPLETE — matching
 * [DownloadEntity]'s own invariant that localFilePath is null for every
 * other status.
 *
 * [includeCoverArt] defaults to `true` (safe default) but every list-sized
 * caller that never actually renders per-track art
 * (Songs/Favorites-tracks/an-album's-own-tracks/a-playlist's-own-tracks/
 * Top-songs — confirmed by grepping every real `track.coverArtUrl` read in
 * the app: only [com.musicplus.app.SearchScreen]'s result rows and
 * [com.musicplus.app.PlayerScreen]'s no-hint-and-no-album-match fallback
 * actually use it) passes `false`. Building a cover art URL means a fresh
 * Subsonic auth token per track (salt + MD5 — see
 * [SubsonicClient.md5Hex]'s doc for why that's real, measured cost, not
 * free), so computing it unconditionally for every track in a several-
 * thousand-row library was pure waste for every screen that can't display
 * it anyway — confirmed live, 2026-09-18: this was the other real
 * contributor (alongside the md5Hex fix) to Songs' full-library load
 * measuring several real seconds on-device.
 */
fun TrackEntity.toTrack(apiHolder: ApiHolder, download: DownloadEntity?, includeCoverArt: Boolean = true) = Track(
    id, title, albumId, albumName, artistId, artistName, trackNumber, durationSec,
    if (includeCoverArt) coverArtId?.let { apiHolder.peekFor(it)?.coverArtUrl(it) } else null, starred,
    downloadStatus = download?.status,
    localFilePath = download?.localFilePath?.takeIf { download.status == DownloadStatus.COMPLETE },
)
