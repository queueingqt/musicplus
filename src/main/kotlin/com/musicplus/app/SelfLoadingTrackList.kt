package com.musicplus.app

import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaylistRepository
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart

/**
 * A track list that refreshes its own backing Room cache before anything gets
 * to read it, keyed by how the tracks are actually sourced (an album via
 * [LibraryRepository], or a playlist via [PlaylistRepository]).
 *
 * This is the fix for "Fix downloading an album/playlist from its list row
 * silently doing nothing" generalized: [LibraryRepository.observeTracksByAlbum]
 * / [PlaylistRepository.observeTracks] are pure Room-cache reads, and that
 * cache is only ever populated by a matching refreshAlbumDetail/
 * refreshPlaylistDetail call. That dependency isn't visible in
 * TrackListDownload.kt's Flow<List<Track>> signature at all, which is exactly
 * how 3 of 4 call sites shipped without it — each one had to independently
 * rediscover and hand-patch the same bug. Rather than trust every future
 * caller to remember the same tribal knowledge, refresh-then-read is baked
 * into this type instead: there is no way to reach [observeDownloadState],
 * [toggleDownload], or [tracks] here without the refresh happening first, for
 * *both* the observed state and the toggle — an unrefreshed album/playlist
 * previously also reported a flat-out wrong NONE download state (empty track
 * list short-circuits [observeTrackListDownloadState]), not just a
 * toggle that silently enqueued nothing.
 */
class SelfLoadingTrackList private constructor(
    private val tracksFlow: Flow<List<Track>>,
    private val refresh: suspend () -> Unit,
) {
    companion object {
        fun forAlbum(libraryRepository: LibraryRepository, albumId: String): SelfLoadingTrackList =
            SelfLoadingTrackList(
                tracksFlow = libraryRepository.observeTracksByAlbum(albumId),
                refresh = { libraryRepository.refreshAlbumDetail(albumId) },
            )

        fun forPlaylist(playlistRepository: PlaylistRepository, playlistId: String): SelfLoadingTrackList =
            SelfLoadingTrackList(
                tracksFlow = playlistRepository.observeTracks(playlistId),
                refresh = { playlistRepository.refreshPlaylistDetail(playlistId) },
            )
    }

    /** See TrackListDownload.kt's [observeTrackListDownloadState] — refreshes before the first emission so a list row for an album/playlist whose detail screen was never opened reports its real download state instead of a guaranteed-empty-cache NONE. */
    fun observeDownloadState(downloadRepository: DownloadRepository): Flow<TrackListDownloadState> =
        observeTrackListDownloadState(tracksFlow, downloadRepository).onStart { refresh() }

    /** See TrackListDownload.kt's [toggleTrackListDownload]. */
    suspend fun toggleDownload(lightContext: SealedLightContext, downloadRepository: DownloadRepository): TrackListDownloadState {
        refresh()
        return toggleTrackListDownload(lightContext, tracksFlow, downloadRepository)
    }

    /** The tracks themselves, refreshed first — e.g. "Add album to queue" needs the real list, not just whatever happened to already be cached. */
    suspend fun tracks(): List<Track> {
        refresh()
        return tracksFlow.first()
    }
}
