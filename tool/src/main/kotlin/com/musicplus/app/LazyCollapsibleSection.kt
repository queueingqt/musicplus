package com.musicplus.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State for a section that starts collapsed and fetches its contents on
 * first expand only — not eagerly on screen show, and never refetched on a
 * later collapse/re-expand (this data isn't expected to change mid-visit,
 * same "search on submit, not as-you-type" spirit as SearchScreen's own
 * one-shot fetches). ArtistDetailScreen's "Similar artists" and "Top songs"
 * sections both independently hand-rolled this exact shape (expanded/
 * loading/items `StateFlow`s plus a private `loaded` guard and a toggle()
 * doing the same four things) — confirmed live, 2026-09-18 architecture
 * review.
 */
class LazyCollapsibleSection<T>(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> List<T>,
) {
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items

    private var loaded = false

    fun toggle() {
        _expanded.value = !_expanded.value
        if (_expanded.value && !loaded) {
            loaded = true
            _loading.value = true
            scope.launch {
                _items.value = fetch()
                _loading.value = false
            }
        }
    }
}
