package com.musicplus.app

import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaylistRepository
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
 * into [toggleDownload]/[tracks] — the two places that actually act on the
 * tracks and where a stale/empty cache silently does the wrong thing.
 *
 * [observeDownloadState] deliberately does NOT refresh on every collection.
 * It used to (`.onStart { refresh() }`), on the reasoning that an unrefreshed
 * album/playlist otherwise shows a flat-out wrong NONE state instead of its
 * real one. Reported live as a severe regression instead: every screen that
 * lists many albums/playlists (AlbumListScreen, ArtistDetailScreen,
 * PlaylistListScreen) collects this per row via `remember(id) { ... }` inside
 * a `LazyColumn`, which disposes and recomposes rows as they scroll out of
 * and back into view — so every row scrolling into view fired a real network
 * request to refresh that album/playlist, repeatedly, during scrolling.
 * Showing NONE for a never-visited album until it's actually acted on (which
 * does refresh first) is the correct tradeoff; a passive glance at a list
 * should never by itself trigger network traffic.
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

    /** See TrackListDownload.kt's [observeTrackListDownloadState] — reads the cache as-is, no refresh (see the class doc for why: this is collected per-row in scrolling lists, and a refresh here means a network call on every row scrolled into view). May show NONE for an album/playlist that's never been refreshed, until [toggleDownload] actually runs. */
    fun observeDownloadState(downloadRepository: DownloadRepository): Flow<TrackListDownloadState> =
        observeTrackListDownloadState(tracksFlow, downloadRepository)

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
