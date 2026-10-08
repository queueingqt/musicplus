package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Artist
import com.musicplus.app.Playlist
import com.musicplus.app.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Tells rows from different servers apart, and only where it matters: a row gets its server's name when the same
 * thing (an album with the same name and artist, a song with the same title and artist, an artist with the same
 * name) is also shown from another server, so two rows never look identical. Playlists always carry their home.
 * A server removed with its downloads kept reads "<name> (removed)".
 */
object ServerLabels {
    fun nameOf(serverId: String?): String? = ServerStandingNow.current().nameOf(serverId)

    /** [items] with their server labels filled in as described above. Cheap when only one server's rows are shown. */
    private fun <T> label(
        items: List<T>,
        always: Boolean,
        keyOf: (T) -> String,
        serverOf: (T) -> String?,
        withLabel: (T, String) -> T,
    ): List<T> {
        if (items.isEmpty()) return items
        val standing = ServerStandingNow.current()
        if (!always && items.mapNotNullTo(HashSet(), serverOf).size < 2) return items
        val serversByKey = HashMap<String, MutableSet<String>>()
        if (!always) {
            for (item in items) {
                val server = serverOf(item) ?: continue
                serversByKey.getOrPut(keyOf(item)) { HashSet() } += server
            }
        }
        return items.map { item ->
            val name = standing.nameOf(serverOf(item))
            if (name != null && (always || (serversByKey[keyOf(item)]?.size ?: 0) > 1)) withLabel(item, name) else item
        }
    }

    private fun key(vararg parts: String?) = parts.joinToString("|") { it.orEmpty().trim().lowercase() }

    fun artists(items: List<Artist>) = label(items, false, { key(it.name) }, { ServerScope.serverOf(it.id) }) { a, n -> a.copy(serverLabel = n) }

    fun albums(items: List<Album>) =
        label(items, false, { key(it.name, it.artistName) }, { ServerScope.serverOf(it.id) }) { a, n -> a.copy(serverLabel = n) }

    fun tracks(items: List<Track>) =
        label(items, false, { key(it.title, it.artistName) }, { ServerScope.serverOf(it.id) }) { t, n -> t.copy(serverLabel = n) }

    /**
     * Favorites of a server that cannot keep them are kept on the phone only, and say so: "Phone Only" replaces the
     * server's name on those rows.
     */
    private fun phoneOnly(standing: ServerStanding, id: String) = !standing.keepsFavorites(ServerScope.serverOf(id))

    // One look at the servers' standing per list, not one per row.
    fun favoriteArtists(items: List<Artist>) =
        ServerStandingNow.current().let { standing -> artists(items).map { if (phoneOnly(standing, it.id)) it.copy(serverLabel = ServerScope.PHONE_ONLY_LABEL) else it } }

    fun favoriteAlbums(items: List<Album>) =
        ServerStandingNow.current().let { standing -> albums(items).map { if (phoneOnly(standing, it.id)) it.copy(serverLabel = ServerScope.PHONE_ONLY_LABEL) else it } }

    fun favoriteTracks(items: List<Track>) =
        ServerStandingNow.current().let { standing -> tracks(items).map { if (phoneOnly(standing, it.id)) it.copy(serverLabel = ServerScope.PHONE_ONLY_LABEL) else it } }

    fun playlists(items: List<Playlist>) =
        label(items, true, { key(it.name) }, { ServerScope.serverOf(it.id) }) { p, n -> p.copy(serverLabel = n) }

    /**
     * [this] again whenever the servers change (a rename, one removed): the names come from the warmed server list, so a
     * flow that labels its rows has to be re-run then, not only when its rows change.
     */
    fun <T> Flow<List<T>>.labelledBy(labeler: (List<T>) -> List<T>): Flow<List<T>> =
        combine(this, ServerStandingNow.changes) { items, _ -> labeler(items) }
}
