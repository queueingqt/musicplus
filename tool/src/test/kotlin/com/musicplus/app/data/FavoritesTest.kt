package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A heart set anywhere reaches the playing queue's copy of the song, online or not. */
class FavoritesTest {
    private val queue = FakeQueue()
    private val local = FakeLocalCopy()
    private val one = RecordingServer("one")
    private val patched = mutableListOf<Pair<String, Boolean>>()

    private val writes = LocalFirstWrites(
        queue = queue,
        local = local,
        apis = FakeApiLookup(mapOf("one" to one)),
        enabledServerIds = object : Flow<Set<String>> { override suspend fun collect(collector: FlowCollector<Set<String>>) = collector.emit(setOf("one")) },
        keepsFavorites = { true },
        reconcile = {},
    )
    private val favorites = Favorites(writes) { id, on -> patched += id to on }

    @Test
    fun aSongsHeartIsWrittenAndPatchedIntoTheQueue() = runBlocking<Unit> {
        favorites.set(FavoriteKind.TRACK, "one:song", true)
        assertEquals(listOf("one:song" to true), patched)
        assertEquals(listOf("star one:song"), one.calls.toList())
        assertEquals(true, local.starred["one:song"])
    }

    @Test
    fun theQueueFollowsEvenWhenTheServerCannotBeReached() = runBlocking<Unit> {
        one.offline = true
        favorites.set(FavoriteKind.TRACK, "one:song", true)
        assertEquals(listOf("one:song" to true), patched, "the phone has the heart whatever the server says")
        assertEquals(1, queue.rows.size, "and it waits in the queue to be sent")
    }

    @Test
    fun anAlbumOrArtistHeartLeavesTheQueueAlone() = runBlocking<Unit> {
        favorites.set(FavoriteKind.ALBUM, "one:album", true)
        favorites.set(FavoriteKind.ARTIST, "one:artist", false)
        assertTrue(patched.isEmpty())
        assertEquals(listOf("star one:album", "unstar one:artist"), one.calls.toList())
    }
}
