package com.musicplus.app

import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons

/**
 * The download state table, written once: what each [DownloadStatus] of a song means, what a tap on its download control does,
 * and how the states of a list's songs add up to one for the list. The same table was copied into five ViewModels and the player
 * (each `toggleDownload`), and the list-level rule sat beside the menu row that showed it.
 */
fun DownloadStatus?.isInProgress(): Boolean = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING

/** A tap on a song's download control: QUEUED/DOWNLOADING/COMPLETE stop it or remove the copy; FAILED and never-started start it. */
fun downloadTapStarts(status: DownloadStatus?): Boolean = status == null || status == DownloadStatus.FAILED

/** What the song's status is once a tap has been carried out, for the control to show at once: QUEUED after a start, null (nothing there) after a stop or remove. */
fun statusAfterDownloadTap(status: DownloadStatus?): DownloadStatus? = if (downloadTapStarts(status)) DownloadStatus.QUEUED else null

/** Carries out a tap on [track]'s download control (it was showing [status]) and returns what it shows now. `cancel` deletes the file and the row whatever the job's state, so it is also "remove the copy". */
suspend fun DownloadRepository.tap(lightContext: SealedLightContext, track: Track, status: DownloadStatus?): DownloadStatus? {
    if (downloadTapStarts(status)) enqueue(lightContext, track) else cancel(lightContext, track.id)
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
