package com.musicplus.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides *when* each list page's data is re-checked against the server: every
 * time the page is opened, silently, and again whenever the app starts or the
 * phone comes back online. What a refresh does is [LibraryRepository] /
 * [PlaylistRepository]'s business (additions, changes and deletions — see
 * [mirrorFromServer]); this class only starts them.
 *
 * The page itself never waits on any of it. It opens straight from the cache
 * ([AppLibraryCache] — that is the whole point of that cache), and the refresh
 * runs on its own process-lifetime scope, so it neither blocks the page nor is
 * abandoned half way if the person leaves it (a half-finished pass never
 * removes anything — see [mirrorFromServer]). Whatever it finds reaches the
 * list through Room's own change notifications, with no spinner and no
 * reload.
 *
 * Two guards keep "every time a page opens" from becoming a request storm:
 * a refresh of a list that is already being refreshed is skipped, and so is one
 * for a list refreshed less than [OPEN_COOLDOWN_MS] ago — opening an album and
 * coming back to the Albums page shouldn't re-walk the whole library. Coming
 * back online ignores the cooldown, since the last attempt can't have worked.
 * A forced refresh (that, or a server being switched on or added) that arrives
 * while a pass is running is not dropped: the pass under way may have started
 * without that server, so it runs once more when the pass finishes.
 */
class ListRefresher(
    private val scope: CoroutineScope,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
) {
    enum class Target { ARTISTS, ALBUMS, SONGS, PLAYLISTS, FAVORITES }

    private val inFlight = Target.values().associateWith { AtomicBoolean(false) }
    private val rerunRequested = Target.values().associateWith { AtomicBoolean(false) }
    private val lastFinishedAtMs = ConcurrentHashMap<Target, Long>()

    /** A list page was opened. Returns immediately; the refresh happens in the background. */
    fun refreshOnOpen(target: Target) = start(target, respectCooldown = true)

    /** App start, or connectivity came back: everything, regardless of when it last ran. */
    fun refreshAll() = Target.values().forEach { start(it, respectCooldown = false) }

    private fun start(target: Target, respectCooldown: Boolean) {
        if (respectCooldown) {
            val last = lastFinishedAtMs[target]
            if (last != null && System.currentTimeMillis() - last < OPEN_COOLDOWN_MS) return
        }
        if (!inFlight.getValue(target).compareAndSet(false, true)) {
            if (!respectCooldown) rerunRequested.getValue(target).set(true)
            return
        }
        scope.launch {
            try {
                when (target) {
                    Target.ARTISTS -> libraryRepository.refreshArtists()
                    Target.ALBUMS -> libraryRepository.refreshAlbumList()
                    Target.SONGS -> libraryRepository.refreshAllSongs()
                    Target.PLAYLISTS -> playlistRepository.refreshPlaylists()
                    Target.FAVORITES -> libraryRepository.refreshFavorites()
                }
            } finally {
                lastFinishedAtMs[target] = System.currentTimeMillis()
                inFlight.getValue(target).set(false)
                if (rerunRequested.getValue(target).getAndSet(false)) start(target, respectCooldown = false)
            }
        }
    }

    private companion object {
        const val OPEN_COOLDOWN_MS = 30_000L
    }
}
