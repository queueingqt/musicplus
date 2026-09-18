package com.musicplus.app

import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

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

private val IN_PROGRESS_DOWNLOAD_STATUSES = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)

fun observeTrackListDownloadState(
    tracksFlow: Flow<List<Track>>,
    downloadRepository: DownloadRepository,
): Flow<TrackListDownloadState> =
    combine(tracksFlow, downloadRepository.observeAll()) { trackList, downloads ->
        if (trackList.isEmpty()) return@combine TrackListDownloadState.NONE
        val statusById = downloads.associateBy { it.songId }
        val completeCount = trackList.count { statusById[it.id]?.status == DownloadStatus.COMPLETE }
        val anyInProgress = trackList.any { statusById[it.id]?.status in IN_PROGRESS_DOWNLOAD_STATUSES }
        when {
            anyInProgress -> TrackListDownloadState.IN_PROGRESS
            completeCount == trackList.size -> TrackListDownloadState.ALL
            completeCount > 0 -> TrackListDownloadState.SOME
            else -> TrackListDownloadState.NONE
        }
    }

/**
 * ALL/IN_PROGRESS -> cancel every track's download; NONE/SOME -> download
 * whatever isn't already COMPLETE (not everything unconditionally — that
 * would silently re-download already-complete tracks). Returns the state
 * that just started, not a guess at what will eventually finish — see
 * [TrackListDownloadState]'s own doc for why that distinction matters.
 */
suspend fun toggleTrackListDownload(
    lightContext: SealedLightContext,
    tracksFlow: Flow<List<Track>>,
    downloadRepository: DownloadRepository,
): TrackListDownloadState {
    val currentTracks = tracksFlow.first()
    val currentState = observeTrackListDownloadState(tracksFlow, downloadRepository).first()
    return when (currentState) {
        TrackListDownloadState.ALL, TrackListDownloadState.IN_PROGRESS -> {
            currentTracks.forEach { downloadRepository.cancel(lightContext, it.id) }
            TrackListDownloadState.NONE
        }
        TrackListDownloadState.NONE, TrackListDownloadState.SOME -> {
            val completeIds = downloadRepository.observeAll().first()
                .filter { it.status == DownloadStatus.COMPLETE }
                .map { it.songId }
                .toSet()
            currentTracks.filter { it.id !in completeIds }.forEach { downloadRepository.enqueue(lightContext, it) }
            TrackListDownloadState.IN_PROGRESS
        }
    }
}

/** Shared icon/label so every track-list download row (album detail/list, artist's albums, a playlist) looks and reads identically, just with [noun] swapped in ("album", "playlist"). */
fun trackListDownloadActionItem(
    noun: String,
    state: TrackListDownloadState,
    toggle: suspend () -> TrackListDownloadState,
): ActionMenuItem = ActionMenuItem(
    key = "download",
    icon = when (state) {
        TrackListDownloadState.ALL -> com.thelightphone.sdk.ui.LightIcons.DOWNLOADED_ARROW
        TrackListDownloadState.IN_PROGRESS -> com.thelightphone.sdk.ui.LightIcons.REFRESH
        TrackListDownloadState.SOME, TrackListDownloadState.NONE -> com.thelightphone.sdk.ui.LightIcons.DOWNLOAD_ARROW
    },
    label = when (state) {
        TrackListDownloadState.ALL -> "Downloaded — remove"
        TrackListDownloadState.IN_PROGRESS -> "Downloading — tap to cancel"
        TrackListDownloadState.SOME -> "Some tracks downloaded — download the rest"
        TrackListDownloadState.NONE -> "Download $noun"
    },
    onSelect = ActionMenuSelection.Perform {
        trackListDownloadActionItem(noun, toggle(), toggle)
    },
)
