package com.musicplus.app

import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Everything about how a download looks and behaves to the person, in one file: the state table (what each [DownloadStatus] of a song
 * means, what a tap on its control does, how a list's songs add up to one state), the menu rows that show and act on it (one for a song,
 * one for a list of songs), and the list-level and whole-artist downloads. The same table was copied into five ViewModels and the
 * player (each `toggleDownload`), and the rows and list rules lived in TrackActionItems.kt and a separate list file. The player's own
 * download control is [tap] too (see PlayerScreen); the artist menu's "Download entire artist" row is ArtistActions'.
 */
fun DownloadStatus?.isInProgress(): Boolean = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING

/** A tap on a song's download control: QUEUED/DOWNLOADING/COMPLETE stop it or remove the copy; FAILED and never-started start it. */
fun downloadTapStarts(status: DownloadStatus?): Boolean = status == null || status == DownloadStatus.FAILED

/** What the song's status is once a tap has been carried out, for the control to show at once: QUEUED after a start, null (nothing there) after a stop or remove. */
fun statusAfterDownloadTap(status: DownloadStatus?): DownloadStatus? = if (downloadTapStarts(status)) DownloadStatus.QUEUED else null

/** Carries out a tap on [track]'s download control (it was showing [status]) and returns what it shows now. `cancel` deletes the file and the row whatever the job's state, so it is also "remove the copy". */
suspend fun DownloadRepository.tap(track: Track, status: DownloadStatus?): DownloadStatus? {
    if (downloadTapStarts(status)) enqueue(track) else cancel(track.id)
    return statusAfterDownloadTap(status)
}

/**
 * Three real visual states, not two: QUEUED/DOWNLOADING render distinctly from both "not downloaded" and "downloaded". Uses REFRESH for
 * "in progress" — LOOP was tried first but is the exact same icon the Now Playing screen uses for Repeat, which on-device looked like a
 * stray repeat toggle appearing on tracks whenever an album download was running. There's no dedicated spinner/progress icon in
 * LightIcons; REFRESH isn't used anywhere else in this app, so it doesn't collide.
 */
fun downloadStatusIcon(status: DownloadStatus?): LightIconConfiguration = when (status) {
    DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.REFRESH
    DownloadStatus.FAILED, null -> LightIcons.DOWNLOAD_ARROW
}

/** What the control says, matching what a tap on it does (see [downloadTapStarts]). */
fun downloadStatusLabel(status: DownloadStatus?): String = when (status) {
    null -> "Download"
    DownloadStatus.QUEUED -> "Queued — tap to cancel"
    DownloadStatus.DOWNLOADING -> "Downloading — tap to cancel"
    DownloadStatus.COMPLETE -> "Downloaded — tap to remove"
    DownloadStatus.FAILED -> "Failed — tap to retry"
}

/**
 * How the songs of a list add up: IN_PROGRESS when any is queued or downloading right now, ALL when every one is downloaded, SOME when
 * some are and all have settled, else NONE (and NONE for an empty list). IN_PROGRESS is its own state, not folded into ALL/SOME: an
 * earlier version only ever looked at COMPLETE counts, so "just started, none done yet" and "fully downloaded" were indistinguishable.
 */
fun trackListDownloadState(songIds: List<String>, statusById: Map<String, DownloadStatus>): TrackListDownloadState {
    if (songIds.isEmpty()) return TrackListDownloadState.NONE
    val complete = songIds.count { statusById[it] == DownloadStatus.COMPLETE }
    return when {
        songIds.any { statusById[it].isInProgress() } -> TrackListDownloadState.IN_PROGRESS
        complete == songIds.size -> TrackListDownloadState.ALL
        complete > 0 -> TrackListDownloadState.SOME
        else -> TrackListDownloadState.NONE
    }
}

/**
 * Per-track counterpart to [trackListDownloadActionItem]
 * — same self-updating shape (the row rebuilds itself from whatever [toggle]
 * returns), but for one track's own [DownloadStatus] rather than a whole
 * album/playlist's aggregate state. [toggle] receives the status this row was
 * just showing and returns the new one — the same contract as each screen's
 * own `toggleDownload(lightContext, track, currentStatus)`, which stays put
 * on each ViewModel rather than moving here, since it's the one part of this
 * that actually needs repository/lightContext access. Callers wanting this
 * row to also track a live status (the same `liveUpdates` pattern
 * [trackListDownloadActionItem] uses) attach it themselves, e.g.
 * `trackDownloadActionItem(status, toggle).copy(liveUpdates = statusFlow.map
 * { trackDownloadActionItem(it, toggle) })`.
 */
fun trackDownloadActionItem(
    status: DownloadStatus?,
    toggle: suspend (DownloadStatus?) -> DownloadStatus?,
): ActionMenuItem = ActionMenuItem(
    key = "download",
    icon = downloadStatusIcon(status),
    label = downloadStatusLabel(status),
    onSelect = ActionMenuSelection.Perform {
        trackDownloadActionItem(toggle(status), toggle)
    },
)

/**
 * NONE (nothing downloaded), SOME (a mix, all settled — tapping downloads
 * the rest), IN_PROGRESS (at least one track actively queued/downloading
 * right now), ALL (every track downloaded). IN_PROGRESS is its own state,
 * not folded into ALL/SOME — reported live: an earlier version only ever
 * looked at COMPLETE counts, so "just started, zero actually done yet" and
 * "fully downloaded" were indistinguishable, and tapping "Download album"
 * instantly claimed "Downloaded".
 *
 * Shared by every screen with a track-list-level download affordance (album
 * detail/list, an artist's own album list, a playlist) rather than a copy of
 * the same combine/toggle logic per track-list type — nothing here is
 * actually album- or playlist-specific, it just needs "the tracks" and "the
 * download index."
 */
enum class TrackListDownloadState { NONE, SOME, IN_PROGRESS, ALL }

fun observeTrackListDownloadState(
    tracksFlow: Flow<List<Track>>,
    downloadRepository: DownloadRepository,
): Flow<TrackListDownloadState> =
    combine(tracksFlow, downloadRepository.observeAll()) { trackList, downloads ->
        trackListDownloadState(trackList.map { it.id }, downloads.associate { it.songId to it.status })
    }

/**
 * ALL/IN_PROGRESS -> cancel every track's download; NONE/SOME -> download
 * whatever isn't already COMPLETE (not everything unconditionally — that
 * would silently re-download already-complete tracks). Returns the state
 * that just started, not a guess at what will eventually finish — see
 * [TrackListDownloadState]'s own doc for why that distinction matters.
 */
suspend fun toggleTrackListDownload(
    tracksFlow: Flow<List<Track>>,
    downloadRepository: DownloadRepository,
): TrackListDownloadState {
    val currentTracks = tracksFlow.first()
    val currentState = observeTrackListDownloadState(tracksFlow, downloadRepository).first()
    return when (currentState) {
        TrackListDownloadState.ALL, TrackListDownloadState.IN_PROGRESS -> {
            currentTracks.forEach { downloadRepository.cancel(it.id) }
            TrackListDownloadState.NONE
        }
        TrackListDownloadState.NONE, TrackListDownloadState.SOME -> {
            val completeIds = downloadRepository.observeAll().first()
                .filter { it.status == DownloadStatus.COMPLETE }
                .map { it.songId }
                .toSet()
            currentTracks.filter { it.id !in completeIds }.forEach { downloadRepository.enqueue(it) }
            TrackListDownloadState.IN_PROGRESS
        }
    }
}

/** The icon a track-list download row shows for [state]. */
fun trackListDownloadIcon(state: TrackListDownloadState): LightIconConfiguration = when (state) {
    TrackListDownloadState.ALL -> downloadStatusIcon(DownloadStatus.COMPLETE)
    TrackListDownloadState.IN_PROGRESS -> downloadStatusIcon(DownloadStatus.DOWNLOADING)
    TrackListDownloadState.SOME, TrackListDownloadState.NONE -> downloadStatusIcon(null)
}

/** The label a track-list download row shows for [state], with [noun] swapped in ("album", "playlist"). */
fun trackListDownloadLabel(noun: String, state: TrackListDownloadState): String = when (state) {
    TrackListDownloadState.ALL -> "Downloaded — remove"
    TrackListDownloadState.IN_PROGRESS -> "Downloading — tap to cancel"
    TrackListDownloadState.SOME -> "Some tracks downloaded — download the rest"
    TrackListDownloadState.NONE -> "Download $noun"
}

/** Shared icon/label so every track-list download row (album detail/list, artist's albums, a playlist) looks and reads identically. Rebuilds itself from whatever [toggle] returns. */
fun trackListDownloadActionItem(
    noun: String,
    state: TrackListDownloadState,
    toggle: suspend () -> TrackListDownloadState,
): ActionMenuItem = ActionMenuItem(
    key = "download",
    icon = trackListDownloadIcon(state),
    label = trackListDownloadLabel(noun, state),
    onSelect = ActionMenuSelection.Perform {
        trackListDownloadActionItem(noun, toggle(), toggle)
    },
)

/**
 * Bulk "download entire artist" — reached from ArtistListScreen's long-press
 * menu (moved there from a standalone row on ArtistDetailScreen itself,
 * reported live 2026-09-18: the row plus its own search field took up too
 * much of that screen). Enqueues every track across every one of the
 * artist's albums. Tracks already COMPLETE are skipped via the same
 * completeIds-filtering approach [toggleTrackListDownload] above uses —
 * reused directly here rather than the whole album-level toggle function,
 * since that also handles "cancel if already downloading," which doesn't
 * apply at artist granularity: there's deliberately no artist-level
 * aggregate download state to cancel (see [TrackListDownloadState]'s own
 * doc for why that was considered and rejected). No progress UI of its own —
 * individual track download progress is still visible the normal way
 * (Downloads screen, per-track glyphs elsewhere) once this enqueues them.
 */
suspend fun downloadEntireArtist(
    libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    artistId: String,
) {
    val completeIds = downloadRepository.observeAll().first()
        .filter { it.status == DownloadStatus.COMPLETE }
        .map { it.songId }
        .toSet()
    val tracks = libraryRepository.observeAlbumsByArtist(artistId).first().flatMap { album ->
        SelfLoadingTrackList.forAlbum(libraryRepository, album.id).tracks()
    }
    tracks.filter { it.id !in completeIds }.forEach { downloadRepository.enqueue(it) }
}
