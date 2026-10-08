package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Artist
import com.musicplus.app.Playlist
import com.musicplus.app.Track

/**
 * Process-lifetime, always-live cache of every list-screen's own "whole
 * collection" data — same [WarmedFlow] primitive already used for
 * App*Prefs, extended here for the same underlying reason: every list
 * screen (Albums/Artists/Songs/Playlists/Favorites) previously got a
 * *fresh* ViewModel on every single visit (confirmed established behavior
 * this whole session — see LightScreen's own `viewModel by lazy` tied to a
 * fresh `ViewModelStore` per screen instance), which meant re-subscribing
 * to `LibraryRepository.observeXxx()`/`PlaylistRepository.observePlaylists()`
 * from scratch — a real Room query plus a real per-row mapping pass — on
 * *every* re-visit, not just the first. For Songs specifically (several
 * thousand tracks) this was measured at multiple real seconds even on a
 * warm, already-running process; smaller collections (Albums/Artists) paid
 * the same tax at a smaller but still real, reported-live scale. Confirmed
 * live, 2026-09-18: navigating away and immediately back showed the exact
 * same empty-then-populate flash every time, since nothing survived the
 * round trip.
 *
 * Mirrored once in [AppGraph.build] (same `mirrorInto` helper App*Prefs
 * uses) — each of these starts collecting its real repository Flow the
 * moment the process's composition root is built (effectively app launch,
 * not first navigation to that specific screen), and stays live for the
 * process's whole lifetime, kept in sync automatically by Room's own Flow
 * invalidation as the cache is written to. A screen visiting any of these
 * collections now just reads an already-resolved `StateFlow.value` — no
 * fresh query, no fresh mapping, no flash of empty state, whether it's the
 * first visit or the fifth.
 *
 * Opening one of these pages also starts a silent background re-check against
 * the server ([ListRefresher]) — deliberately *not* awaited by the page, so it
 * never brings back the per-visit wait this cache exists to remove; whatever
 * it finds flows in through the same Room invalidation that keeps this cache
 * current.
 */
object AppLibraryCache {
    val artists = WarmedFlow<List<Artist>>(emptyList())
    val albums = WarmedFlow<List<Album>>(emptyList())
    val allTracks = WarmedFlow<List<Track>>(emptyList())
    val favoriteArtists = WarmedFlow<List<Artist>>(emptyList())
    val favoriteAlbums = WarmedFlow<List<Album>>(emptyList())
    val favoriteTracks = WarmedFlow<List<Track>>(emptyList())
    val playlists = WarmedFlow<List<Playlist>>(emptyList())
}
