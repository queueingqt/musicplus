package com.musicplus.app.data

/**
 * What a heart does, for an artist, an album or a song, whichever screen it is pressed on. It is written the local-first way
 * ([LocalFirstWrites]: the phone's copy, the server, the queue if the send fails) and, for a song, the playing queue's own copy of it is
 * patched too: Now Playing reads its star from the queue's snapshot, which a write to the phone's copy never touches. That patch used to
 * be made by the player screen alone, so a song favorited from a list's menu (or Search, or Favorites) left Now Playing's star stale.
 */
class Favorites(
    private val writes: LocalFirstWrites,
    /** A song's heart changed: the queue holds a copy of the song and must follow. Whatever the server says, the phone has it. */
    private val onTrackChanged: (trackId: String, favorite: Boolean) -> Unit,
) {
    suspend fun set(kind: FavoriteKind, id: String, favorite: Boolean) {
        if (kind == FavoriteKind.TRACK) onTrackChanged(id, favorite)
        writes.write(id, PendingMutation.Favorite(kind, favorite))
    }
}
