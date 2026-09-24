package com.musicplus.app.data

import com.musicplus.app.Track

/**
 * Shared ApiSong -> TrackEntity -> Track mapping. [LibraryRepository]
 * and [PlaylistRepository] both independently hand-rolled an identical copy
 * of this pair (the latter's own doc comment even called out the
 * duplication as a deliberate choice) — confirmed live, 2026-09-18
 * architecture review.
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
 * The cover art is the entity's scoped cover-art id, carried as it is: art is fetched by id (see [CoverArtStore]), so there is nothing to
 * build per row. It used to be a fully authenticated URL, a fresh auth token (salt and MD5, real measured cost) per track, so every
 * list-sized caller that did not show per-track art had to pass `includeCoverArt = false` to keep Songs' full-library load from
 * taking several seconds, and a row mapped before the API client had been resolved got no art at all.
 */
fun TrackEntity.toTrack(download: DownloadEntity?) = Track(
    id, title, albumId, albumName, artistId, artistName, trackNumber, durationSec, coverArtId, starred,
    downloadStatus = download?.status,
    localFilePath = download?.localFilePath?.takeIf { download.status == DownloadStatus.COMPLETE },
)
