package com.musicplus.app

import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
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
 * Shared by every screen with an album-level download affordance (detail,
 * list, an artist's own album list) rather than three copies of the same
 * combine/toggle logic.
 */
enum class AlbumDownloadState { NONE, SOME, IN_PROGRESS, ALL }

private val IN_PROGRESS_DOWNLOAD_STATUSES = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)

fun observeAlbumDownloadState(
    libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    albumId: String,
): Flow<AlbumDownloadState> =
    combine(libraryRepository.observeTracksByAlbum(albumId), downloadRepository.observeAll()) { trackList, downloads ->
        if (trackList.isEmpty()) return@combine AlbumDownloadState.NONE
        val statusById = downloads.associateBy { it.songId }
        val completeCount = trackList.count { statusById[it.id]?.status == DownloadStatus.COMPLETE }
        val anyInProgress = trackList.any { statusById[it.id]?.status in IN_PROGRESS_DOWNLOAD_STATUSES }
        when {
            anyInProgress -> AlbumDownloadState.IN_PROGRESS
            completeCount == trackList.size -> AlbumDownloadState.ALL
            completeCount > 0 -> AlbumDownloadState.SOME
            else -> AlbumDownloadState.NONE
        }
    }

/**
 * ALL/IN_PROGRESS -> cancel every track's download; NONE/SOME -> download
 * whatever isn't already COMPLETE (not everything unconditionally — that
 * would silently re-download already-complete tracks). Returns the state
 * that just started, not a guess at what will eventually finish — see
 * [AlbumDownloadState]'s own doc for why that distinction matters.
 */
suspend fun toggleAlbumDownload(
    lightContext: SealedLightContext,
    libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    albumId: String,
): AlbumDownloadState {
    val currentTracks = libraryRepository.observeTracksByAlbum(albumId).first()
    val currentState = observeAlbumDownloadState(libraryRepository, downloadRepository, albumId).first()
    return when (currentState) {
        AlbumDownloadState.ALL, AlbumDownloadState.IN_PROGRESS -> {
            currentTracks.forEach { downloadRepository.cancel(lightContext, it.id) }
            AlbumDownloadState.NONE
        }
        AlbumDownloadState.NONE, AlbumDownloadState.SOME -> {
            val completeIds = downloadRepository.observeAll().first()
                .filter { it.status == DownloadStatus.COMPLETE }
                .map { it.songId }
                .toSet()
            currentTracks.filter { it.id !in completeIds }.forEach { downloadRepository.enqueue(lightContext, it) }
            AlbumDownloadState.IN_PROGRESS
        }
    }
}

/** Shared icon/label so every album-download row (detail, list, artist's albums) looks and reads identically. */
fun albumDownloadActionItem(
    state: AlbumDownloadState,
    toggle: suspend () -> AlbumDownloadState,
): ActionMenuItem = ActionMenuItem(
    key = "download",
    icon = when (state) {
        AlbumDownloadState.ALL -> com.thelightphone.sdk.ui.LightIcons.DOWNLOADED_ARROW
        AlbumDownloadState.IN_PROGRESS -> com.thelightphone.sdk.ui.LightIcons.REFRESH
        AlbumDownloadState.SOME, AlbumDownloadState.NONE -> com.thelightphone.sdk.ui.LightIcons.DOWNLOAD_ARROW
    },
    label = when (state) {
        AlbumDownloadState.ALL -> "Downloaded — remove"
        AlbumDownloadState.IN_PROGRESS -> "Downloading — tap to cancel"
        AlbumDownloadState.SOME -> "Some tracks downloaded — download the rest"
        AlbumDownloadState.NONE -> "Download album"
    },
    onSelect = ActionMenuSelection.Perform {
        albumDownloadActionItem(toggle(), toggle)
    },
)
