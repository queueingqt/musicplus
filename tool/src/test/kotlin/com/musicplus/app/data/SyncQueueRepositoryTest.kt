package com.musicplus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two things this vocabulary does that are not a one-line forward: rewrite a whole playlist order for a move, and make a playlist at a home. */
class SyncQueueRepositoryTest {
    private val queue = FakeQueue()
    private val local = FakeLocalCopy()
    private val one = RecordingServer("one")
    private val servers = mapOf<String, MusicApi>("one" to one)
    private val copies = mutableListOf<Pair<String, String>>()
    private val phoneFavorites = mutableListOf<Pair<FavoriteKind, String>>()

    private val writes = LocalFirstWrites(
        queue = queue,
        local = local,
        apis = FakeApiLookup(servers),
        enabledServerIds = object : Flow<Set<String>> { override suspend fun collect(collector: FlowCollector<Set<String>>) = collector.emit(setOf("one")) },
        keepsFavorites = { true },
        reconcile = {},
    )

    private val repository = SyncQueueRepository(
        writes = writes,
        favorites = Favorites(writes) { _, _ -> },
        local = local,
        localFavorites = { phoneFavorites.toList() },
        copyToPhone = { playlistId, songId -> copies += playlistId to songId; "phone:copy" },
        apis = FakeApiLookup(servers),
    )

    private fun playlist(id: String, vararg songs: String) {
        local.names[id] = "list"
        local.members[id] = songs.toList()
    }

    // ---- move ----

    @Test
    fun aMoveRewritesTheWholeOrderLocallyAndOnTheServer() = runBlocking<Unit> {
        playlist("one:p", "one:a", "one:b", "one:c")
        repository.moveTrackDown("one:p", 0)
        assertEquals(listOf("one:b", "one:a", "one:c"), local.members["one:p"])
        assertEquals(listOf("reorder one:p one:b,one:a,one:c"), one.calls.toList())
    }

    @Test
    fun anOfflineMoveQueuesTheWholeOrderNotTheDelta() = runBlocking<Unit> {
        playlist("one:p", "one:a", "one:b", "one:c")
        one.offline = true
        repository.moveTrackUp("one:p", 2)
        assertEquals(
            listOf("one:p" to PendingMutation.PlaylistReorder(listOf("one:a", "one:c", "one:b"))),
            queue.mutations(),
            "a replay must not re-run a swap against a copy that has moved on",
        )
    }

    @Test
    fun aMovePastEitherEndDoesNothing() = runBlocking<Unit> {
        playlist("one:p", "one:a", "one:b")
        repository.moveTrackUp("one:p", 0)
        repository.moveTrackDown("one:p", 1)
        repository.moveTrackDown("one:p", 9)
        assertEquals(listOf("one:a", "one:b"), local.members["one:p"])
        assertTrue(queue.rows.isEmpty() && one.calls.isEmpty())
    }

    // ---- createPlaylist ----

    @Test
    fun aPlaylistMadePhoneOnlyStaysOnThePhone() = runBlocking<Unit> {
        val id = repository.createPlaylist("road", ServerScope.PHONE)!!
        assertTrue(ServerScope.isPhone(id))
        assertEquals("road", local.names[id])
        assertTrue(queue.rows.isEmpty() && one.calls.isEmpty())
    }

    @Test
    fun aPlaylistMadeOnAServerThatIsReachableGetsItsRealId() = runBlocking<Unit> {
        val id = repository.createPlaylist("mix", "one")!!
        assertEquals("one:real-1", id)
        assertEquals(listOf("create mix"), one.calls.toList())
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aPlaylistMadeWhileTheServerIsUnreachableIsAPlaceholderAndWaitsInTheQueue() = runBlocking<Unit> {
        one.offline = true
        val id = repository.createPlaylist("mix", "one")!!
        assertTrue(ServerScope.nativeOf(id).startsWith(PLACEHOLDER_PREFIX), "a placeholder id: $id")
        assertEquals("mix", local.names[id], "it shows at once like a real one")
        assertEquals(listOf(id to PendingMutation.PlaylistCreate("mix")), queue.mutations())
    }

    @Test
    fun aPlaylistForAServerThatIsNotSetUpIsNull() = runBlocking<Unit> {
        assertNull(repository.createPlaylist("mix", "nobody"))
        assertTrue(queue.rows.isEmpty() && local.names.isEmpty())
    }

    @Test
    fun twoPlaceholdersNeverShareAnId() = runBlocking<Unit> {
        one.offline = true
        assertNotEquals(repository.createPlaylist("a", "one"), repository.createPlaylist("b", "one"))
    }

    // ---- the two lambdas ----

    @Test
    fun heartsKeptOnThePhoneAreQueuedWhenTheServerGainsFavorites() = runBlocking<Unit> {
        phoneFavorites += FavoriteKind.TRACK to "one:s1"
        phoneFavorites += FavoriteKind.ARTIST to "one:ar"
        repository.sendPhoneOnlyFavorites("one")
        assertEquals(
            listOf("one:s1" to PendingMutation.Favorite(FavoriteKind.TRACK, true), "one:ar" to PendingMutation.Favorite(FavoriteKind.ARTIST, true)),
            queue.mutations(),
        )
        assertTrue(local.starred.isEmpty(), "queued only: the phone already holds them")
    }

    @Test
    fun aPhoneOnlyCopyIsAskedOfTheRepositoryThatCanReadTheOriginal() = runBlocking<Unit> {
        assertEquals("phone:copy", repository.addToPhoneCopy("one:p", "one:s"))
        assertEquals(listOf("one:p" to "one:s"), copies)
    }
}
