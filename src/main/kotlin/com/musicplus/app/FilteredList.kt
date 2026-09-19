package com.musicplus.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Client-side "search within this already-cached list" filter — the exact
 * `combine(source, query) { list, q -> if (q.isBlank()) list else
 * list.filter { ... } }` shape was hand-copied across ArtistListScreen/
 * AlbumListScreen/SongsListScreen/PlaylistListScreen/FavoritesScreen (×3) —
 * confirmed live, 2026-09-18 architecture review. Distinct in purpose from
 * SearchScreen's server-side `search3` call: this never round-trips, it
 * only narrows what's already cached.
 */
fun <T> filteredBy(source: Flow<List<T>>, query: Flow<String>, matches: (T, String) -> Boolean): Flow<List<T>> =
    combine(source, query) { list, q ->
        if (q.isBlank()) list else list.filter { matches(it, q) }
    }
