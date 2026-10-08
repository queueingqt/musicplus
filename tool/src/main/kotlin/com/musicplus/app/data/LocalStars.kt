package com.musicplus.app.data

/**
 * When the phone's own heart wins over what a server says, in one place, for every refresh (#70: the list refreshes protected an
 * offline favourite and the detail refreshes did not, so opening an album could overwrite it with the server's older answer until the
 * queue drained). It wins for a row whose id is [pending], meaning an edit of it is still waiting to be sent, and for a server that
 * cannot keep favorites, which never says a thing is starred, so whatever it sends back has `starred = false` and writing it over a
 * heart made on the phone would quietly erase it at the next refresh. A server that does keep favorites, with nothing pending,
 * is the truth for its own rows.
 */
class LocalStars(
    private val pending: Set<String>,
    private val keepsFavorites: (serverId: String?) -> Boolean = { Capabilities.can(it, Capability.STAR) },
) {
    fun wins(id: String): Boolean = id in pending || !keepsFavorites(ServerScope.serverOf(id))

    /**
     * [items] as they should be written or shown: each row the phone's heart wins for gets the heart the phone holds (either way: an
     * un-heart made offline survives too), the rest are the server's. [loadLocal] reads the phone's rows for the ids it is asked about.
     */
    suspend fun <E> keeping(
        items: List<E>,
        idOf: (E) -> String,
        isStarred: (E) -> Boolean,
        withStarred: (E, Boolean) -> E,
        loadLocal: suspend (List<String>) -> List<E>,
    ): List<E> {
        val candidates = items.filter { wins(idOf(it)) }
        if (candidates.isEmpty()) return items
        val local = candidates.map(idOf).chunked(500).flatMap { loadLocal(it) }.associate { idOf(it) to isStarred(it) }
        if (local.isEmpty()) return items
        return items.map { item -> local[idOf(item)]?.takeIf { wins(idOf(item)) }?.let { withStarred(item, it) } ?: item }
    }
}

internal suspend fun ArtistDao.keepingLocalStars(items: List<ArtistEntity>, stars: LocalStars) =
    stars.keeping(items, ArtistEntity::id, ArtistEntity::starred, { e, s -> e.copy(starred = s) }) { getByIds(it) }

internal suspend fun AlbumDao.keepingLocalStars(items: List<AlbumEntity>, stars: LocalStars) =
    stars.keeping(items, AlbumEntity::id, AlbumEntity::starred, { e, s -> e.copy(starred = s) }) { getByIds(it) }

internal suspend fun TrackDao.keepingLocalStars(items: List<TrackEntity>, stars: LocalStars) =
    stars.keeping(items, TrackEntity::id, TrackEntity::starred, { e, s -> e.copy(starred = s) }) { getByIds(it) }
