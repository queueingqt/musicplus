package com.musicplus.app.data

/**
 * A server that cannot keep favorites never says a thing is starred, so whatever it sends back has `starred = false`. A
 * heart made on the phone for one of its songs (see [LibraryRepository.setFavorite]) lives only in the cached row, and
 * writing the server's version over that row would quietly erase it at the next refresh. These put the phone's hearts back
 * on rows about to be written, or shown from a live answer, for such a server. Rows from a server that does keep favorites
 * are untouched: its answer is the truth there.
 */
private suspend fun <E> keepPhoneStars(
    items: List<E>,
    idOf: (E) -> String,
    isStarred: (E) -> Boolean,
    loadByIds: suspend (List<String>) -> List<E>,
    withStar: (E) -> E,
): List<E> {
    val candidates = items.filter { !isStarred(it) && Capabilities.cannot(ServerScope.serverOf(idOf(it)), Capability.STAR) }
    if (candidates.isEmpty()) return items
    val hearted = candidates.map(idOf).chunked(500).flatMap { loadByIds(it) }.filter(isStarred).mapTo(HashSet(), idOf)
    return if (hearted.isEmpty()) items else items.map { if (idOf(it) in hearted) withStar(it) else it }
}

internal suspend fun ArtistDao.keepingPhoneStars(items: List<ArtistEntity>) =
    keepPhoneStars(items, ArtistEntity::id, ArtistEntity::starred, { getByIds(it) }) { it.copy(starred = true) }

internal suspend fun AlbumDao.keepingPhoneStars(items: List<AlbumEntity>) =
    keepPhoneStars(items, AlbumEntity::id, AlbumEntity::starred, { getByIds(it) }) { it.copy(starred = true) }

internal suspend fun TrackDao.keepingPhoneStars(items: List<TrackEntity>) =
    keepPhoneStars(items, TrackEntity::id, TrackEntity::starred, { getByIds(it) }) { it.copy(starred = true) }
