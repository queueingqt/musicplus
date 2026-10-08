package com.musicplus.app

import com.musicplus.app.data.LibraryState
import com.musicplus.app.data.ServerScope

/**
 * What "Clear all local data?" tells the person before it deletes. It is the last thing read before something that cannot be undone, so it lives
 * here as a plain function with exact-text tests instead of inside a click handler.
 *
 * Phone Only playlists, and the hearts kept on the phone for a server that cannot keep favorites, exist nowhere else: they are the one thing
 * this clears that cannot be brought back from a server, so they are named first, with a count. A change still waiting to sync is lost too.
 */
fun clearAllDataMessage(phoneOnlyPlaylists: Int, phoneOnlyFavorites: Int, pendingChanges: Int): String = buildString {
    if (phoneOnlyPlaylists > 0 || phoneOnlyFavorites > 0) {
        val parts = buildList {
            if (phoneOnlyPlaylists > 0) add(if (phoneOnlyPlaylists == 1) "1 Phone Only playlist" else "$phoneOnlyPlaylists Phone Only playlists")
            if (phoneOnlyFavorites > 0) add(if (phoneOnlyFavorites == 1) "1 favorite kept only on this phone" else "$phoneOnlyFavorites favorites kept only on this phone")
        }
        val total = phoneOnlyPlaylists + phoneOnlyFavorites
        append("This permanently deletes ${parts.joinToString(" and ")}. ${if (total == 1) "It exists" else "They exist"} nowhere else. ")
    }
    append("Removes downloaded music, cached artwork and lyrics, and the library cache. Your server login stays saved.")
    if (pendingChanges > 0) {
        append(if (pendingChanges == 1) " 1 change waiting to sync will be lost." else " $pendingChanges changes waiting to sync will be lost.")
    }
}

/** How many playlists exist only on this phone. */
fun LibraryState.phoneOnlyPlaylistCount(): Int = playlists.value.value.count { ServerScope.isPhone(it.id) }

/** How many hearts (songs, albums, artists) are kept only on this phone because their server cannot keep favorites. */
fun LibraryState.phoneOnlyFavoriteCount(): Int =
    favoriteTracks.value.value.count { it.serverLabel == ServerScope.PHONE_ONLY_LABEL } +
        favoriteAlbums.value.value.count { it.serverLabel == ServerScope.PHONE_ONLY_LABEL } +
        favoriteArtists.value.value.count { it.serverLabel == ServerScope.PHONE_ONLY_LABEL }
