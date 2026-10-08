package com.musicplus.app.data

import com.musicplus.app.WriteOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue's rows, in memory. */
class FakeQueue : PendingMutationDao {
    val rows = CopyOnWriteArrayList<PendingMutationEntity>()
    private var nextId = 1L
    private val count = MutableStateFlow(0)
    private fun sync() { count.value = rows.size }

    override fun observeAll(): Flow<List<PendingMutationEntity>> = flowOf(rows.toList())
    override suspend fun getAllInOrder() = rows.sortedBy { it.id }
    override fun observeCount(): Flow<Int> = count
    override fun observePendingForTarget(type: String, targetId: String): Flow<Boolean> = count.map { rows.any { it.type == type && it.targetId == targetId } }
    override suspend fun getTargetIdsByType(type: String) = rows.filter { it.type == type }.map { it.targetId }
    override suspend fun insert(mutation: PendingMutationEntity): Long = nextId++.also { rows += mutation.copy(id = it); sync() }
    override suspend fun delete(id: Long) { rows.removeAll { it.id == id }; sync() }
    override suspend fun deleteForTarget(targetId: String) { rows.removeAll { it.targetId == targetId }; sync() }
    override suspend fun reassignTarget(oldTargetId: String, newTargetId: String) {
        rows.replaceAll { if (it.targetId == oldTargetId) it.copy(targetId = newTargetId) else it }
    }
    override suspend fun recordFailure(id: Long, error: String?) {
        rows.replaceAll { if (it.id == id) it.copy(attemptCount = it.attemptCount + 1, lastError = error) else it }
    }
    override suspend fun deleteForServer(serverId: String) { rows.removeAll { ServerScope.serverOf(it.targetId) == serverId }; sync() }
    override suspend fun deleteAll() { rows.clear(); sync() }

    fun mutations(): List<Pair<String, PendingMutation>> = rows.sortedBy { it.id }.map { it.targetId to Json.decodeFromString<PendingMutation>(it.payloadJson) }
}

/** The phone's copy, in memory. */
class FakeLocalCopy : LocalCopy {
    val starred = HashMap<String, Boolean>()
    val names = HashMap<String, String>()
    val members = HashMap<String, List<String>>()

    override suspend fun setStarred(kind: FavoriteKind, id: String, starred: Boolean) { this.starred[id] = starred }
    override suspend fun playlistName(playlistId: String) = names[playlistId]
    override suspend fun renamePlaylist(playlistId: String, name: String) { if (playlistId in names) names[playlistId] = name }
    override suspend fun deletePlaylist(playlistId: String) { names.remove(playlistId); members.remove(playlistId) }
    override suspend fun songIds(playlistId: String) = members[playlistId].orEmpty()
    override suspend fun setSongIds(playlistId: String, songIds: List<String>) { members[playlistId] = songIds }
    override suspend fun adoptPlaylist(placeholderId: String, name: String) { names[placeholderId] = name; members[placeholderId] = emptyList() }
    override suspend fun reassignPlaylist(oldId: String, newId: String) {
        names.remove(oldId)?.let { names[newId] = it }
        members.remove(oldId)?.let { members[newId] = it }
    }
}

/** A server that records what it is told, and can be made unreachable. */
class RecordingServer(id: String) : StubMusicApi(id) {
    val calls = CopyOnWriteArrayList<String>()
    var offline = false
    /** When set, every call is answered with this error (a server that understood and said no). */
    var answerWith: Exception? = null
    private var created = 0

    private fun call(what: String) {
        if (offline) throw IOException("offline")
        answerWith?.let { throw it }
        calls += what
    }

    override suspend fun star(id: String) = call("star $id")
    override suspend fun unstar(id: String) = call("unstar $id")
    override suspend fun renamePlaylist(playlistId: String, name: String) = call("rename $playlistId $name")
    override suspend fun deletePlaylist(id: String) = call("delete $id")
    override suspend fun addSongToPlaylist(playlistId: String, songId: String) = call("add $playlistId $songId")
    override suspend fun removeSongFromPlaylist(playlistId: String, songIndex: Int) = call("remove $playlistId $songIndex")
    override suspend fun reorderPlaylist(playlistId: String, name: String, songIds: List<String>) = call("reorder $playlistId ${songIds.joinToString(",")}")
    override suspend fun createPlaylist(name: String, songIds: List<String>): ApiPlaylistDetail? {
        call("create $name")
        return ApiPlaylistDetail("$serverId:real-${++created}", name, 0, 0, null, emptyList())
    }
}

/** Offline writes, replay, and the placeholder of a playlist made offline, through the write module's interface with a fake server and an in-memory copy. */
class LocalFirstWritesTest {
    private val queue = FakeQueue()
    private val local = FakeLocalCopy()
    private val one = RecordingServer("one")
    private val two = RecordingServer("two")
    private val reconciled = CopyOnWriteArrayList<String>()
    private var enabled = setOf("one", "two")
    private var keepsFavorites = true
    private val servers = mutableMapOf<String, MusicApi>("one" to one, "two" to two)

    private val writes = LocalFirstWrites(
        queue = queue,
        local = local,
        apis = object : ApiLookup by FakeApiLookup(servers) {
            // Reads the live map so a test can take a server away.
            override suspend fun forId(id: String): MusicApi? = ServerScope.serverOf(id)?.let(servers::get)
            override suspend fun forServer(serverId: String): MusicApi? = servers[serverId]
        },
        enabledServerIds = MutableStateFlow(emptySet<String>()).let { object : Flow<Set<String>> { override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Set<String>>) = collector.emit(enabled) } },
        keepsFavorites = { keepsFavorites },
        reconcile = { reconciled += it },
    )

    private fun playlist(id: String, vararg songs: String) {
        local.names[id] = "list"
        local.members[id] = songs.toList()
    }

    private fun favorite(on: Boolean) = PendingMutation.Favorite(FavoriteKind.TRACK, on)

    // ---- a write ----

    @Test
    fun aWriteThatReachesTheServerIsAppliedLocallySentAndNotQueued() = runBlocking<Unit> {
        val result = writes.write("one:song", favorite(true))
        assertEquals(WriteOutcome.SUCCESS, result.outcome)
        assertEquals(true, local.starred["one:song"])
        assertEquals(listOf("star one:song"), one.calls.toList())
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aFailedWriteIsAppliedLocallyAndQueuedExactlyOnce() = runBlocking<Unit> {
        one.offline = true
        val result = writes.write("one:song", favorite(true))
        assertEquals(WriteOutcome.FAILED, result.outcome)
        assertEquals(true, local.starred["one:song"], "the screen shows it at once, offline or not")
        assertEquals(listOf("one:song" to favorite(true)), queue.mutations())
    }

    @Test
    fun aQueuedFavoriteIsSaidToBePendingSoARefreshDoesNotOverwriteIt() = runBlocking<Unit> {
        one.offline = true
        writes.write("one:song", favorite(true))
        assertEquals(setOf("one:song"), queue.pendingFavoriteIds())
    }

    @Test
    fun nothingIsQueuedWhereThereIsNothingToSendTo() = runBlocking<Unit> {
        playlist("phone:p", "phone:a")
        assertEquals(WriteOutcome.NOT_CONFIGURED, writes.write("phone:p", PendingMutation.PlaylistRename("x")).outcome, "a Phone Only playlist")
        assertEquals("x", local.names["phone:p"], "but the phone's copy changed")
        keepsFavorites = false
        assertEquals(WriteOutcome.NOT_CONFIGURED, writes.write("one:song", favorite(true)).outcome, "a server that keeps no favorites")
        assertEquals(true, local.starred["one:song"], "the heart stays on the phone")
        servers.remove("two")
        assertEquals(WriteOutcome.NOT_CONFIGURED, writes.write("two:song", favorite(true)).outcome, "a server that is not set up")
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aRemoveAtAPositionThatIsNotThereDoesNothingAndIsNotQueued() = runBlocking<Unit> {
        playlist("one:p", "one:a", "one:b")
        one.offline = true
        assertEquals(WriteOutcome.NOT_CONFIGURED, writes.write("one:p", PendingMutation.PlaylistRemoveTrack(7)).outcome)
        assertEquals(listOf("one:a", "one:b"), local.members["one:p"])
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aSuccessfulPlaylistEditIsReadBackFromTheServer() = runBlocking<Unit> {
        playlist("one:p", "one:a")
        writes.write("one:p", PendingMutation.PlaylistAddTrack("one:b"))
        assertEquals(listOf("one:a", "one:b"), local.members["one:p"])
        assertEquals(listOf("add one:p one:b"), one.calls.toList())
        assertEquals(listOf("one:p"), reconciled.toList())
    }

    @Test
    fun aSongOfAnotherServerInAServerPlaylistStaysOnThePhoneAndIsNeverSent() = runBlocking<Unit> {
        playlist("one:p", "one:a")
        val result = writes.write("one:p", PendingMutation.PlaylistAddTrack("two:x"))
        assertEquals(WriteOutcome.NOT_CONFIGURED, result.outcome)
        assertEquals(listOf("one:a", "two:x"), local.members["one:p"])
        assertTrue(one.calls.isEmpty() && queue.rows.isEmpty())
    }

    // ---- replay ----

    /** #69: each failed attempt of an offline add used to insert another local row. */
    @Test
    fun aReplayOnlySendsSoAnOfflineAddIsNeverInsertedTwice() = runBlocking<Unit> {
        playlist("one:p", "one:a", "one:b")
        one.offline = true
        writes.write("one:p", PendingMutation.PlaylistAddTrack("one:x"))
        assertEquals(listOf("one:a", "one:b", "one:x"), local.members["one:p"])

        assertFalse(writes.drain())
        assertFalse(writes.drain())
        assertEquals(listOf("one:a", "one:b", "one:x"), local.members["one:p"], "two failed attempts added nothing")
        assertEquals(2, queue.rows.single().attemptCount)

        one.offline = false
        assertTrue(writes.drain())
        assertEquals(listOf("one:a", "one:b", "one:x"), local.members["one:p"])
        assertEquals(listOf("add one:p one:x"), one.calls.toList())
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aReplayNeverOverwritesWhatThePersonDidMeanwhile() = runBlocking<Unit> {
        playlist("one:p", "one:a", "one:b", "one:c")
        one.offline = true
        writes.write("one:p", PendingMutation.PlaylistReorder(listOf("one:c", "one:a", "one:b")))
        writes.write("one:p", PendingMutation.PlaylistRemoveTrack(0)) // removes one:c, the head after the reorder
        assertEquals(listOf("one:a", "one:b"), local.members["one:p"])
        one.offline = false
        assertTrue(writes.drain())
        assertEquals(listOf("one:a", "one:b"), local.members["one:p"], "the queued reorder did not put the removed song back")
        assertEquals(listOf("reorder one:p one:c,one:a,one:b", "remove one:p 0"), one.calls.toList(), "the server gets both, in order")
    }

    @Test
    fun aServersLineStopsAtItsFirstFailureAndOthersCarryOn() = runBlocking<Unit> {
        one.offline = true
        writes.write("one:s1", favorite(true))
        writes.write("one:s2", favorite(true))
        writes.write("two:s3", favorite(true).copy(favorite = true).let { it })
        two.offline = true
        writes.write("two:s4", favorite(true))
        two.offline = false
        assertEquals(3, queue.rows.size)
        assertFalse(writes.drain())
        assertEquals(listOf("one:s1", "one:s2"), queue.mutations().map { it.first }.filter { it.startsWith("one") }, "one's line stopped at its first failure")
        assertEquals(1, queue.rows.first { it.targetId == "one:s1" }.attemptCount)
        assertEquals(0, queue.rows.first { it.targetId == "one:s2" }.attemptCount, "the second was never tried")
        assertEquals(listOf("star two:s4"), two.calls.filter { it == "star two:s4" }.toList(), "two's edit went through")
    }

    @Test
    fun aServerThatIsOffKeepsItsEditsUntilItIsSwitchedOnAgain() = runBlocking<Unit> {
        one.offline = true
        writes.write("one:s", favorite(true))
        one.offline = false
        enabled = setOf("two")
        assertTrue(writes.drain())
        assertEquals(1, queue.rows.size)
        assertTrue(one.calls.isEmpty())
        enabled = setOf("one", "two")
        assertTrue(writes.drain())
        assertTrue(queue.rows.isEmpty())
        assertEquals(listOf("star one:s"), one.calls.toList())
    }

    @Test
    fun editsThatCanNeverBeSentOrRead_areDroppedRatherThanBlockingTheQueue() = runBlocking<Unit> {
        queue.insert(PendingMutationEntity(type = "FAVORITE", targetId = "phone:x", payloadJson = Json.encodeToString<PendingMutation>(favorite(true)), createdAtEpochMs = 0))
        queue.insert(PendingMutationEntity(type = "FAVORITE", targetId = "no-scope", payloadJson = Json.encodeToString<PendingMutation>(favorite(true)), createdAtEpochMs = 0))
        queue.insert(PendingMutationEntity(type = "OLD_SHAPE", targetId = "one:x", payloadJson = "{not json", createdAtEpochMs = 0))
        one.offline = true
        writes.write("one:s", favorite(true)) // queued after the unreadable one
        one.offline = false
        assertTrue(writes.drain())
        assertTrue(queue.rows.isEmpty(), "the unreadable one did not hold up the valid one behind it")
        assertEquals(listOf("star one:s"), one.calls.toList())
    }

    // ---- a playlist made offline ----

    @Test
    fun aPlaylistMadeOfflineIsAPlaceholderUntilTheCreateReachesTheServerThenEveryEditFollowsItToItsRealId() = runBlocking<Unit> {
        one.offline = true
        val placeholder = "one:pending:abc"
        val created = writes.write(placeholder, PendingMutation.PlaylistCreate("mix"))
        assertEquals(WriteOutcome.FAILED, created.outcome)
        assertEquals("mix", local.names[placeholder], "it shows at once, empty, like a real one")
        writes.write(placeholder, PendingMutation.PlaylistAddTrack("one:a"))
        writes.write(placeholder, PendingMutation.PlaylistRename("mix 2"))
        assertEquals(3, queue.rows.size)

        one.offline = false
        assertTrue(writes.drain())
        assertTrue(queue.rows.isEmpty())
        assertNull(local.names[placeholder])
        assertEquals("mix 2", local.names["one:real-1"], "the row moved to its real id")
        assertEquals(listOf("one:a"), local.members["one:real-1"])
        assertEquals(
            listOf("create mix", "add one:real-1 one:a", "rename one:real-1 mix 2"),
            one.calls.toList(),
            "the later edits were sent to the real id, in order",
        )
    }

    @Test
    fun aCreateThatReachesTheServerAtOnceHasItsRealIdAsItsResult() = runBlocking<Unit> {
        val result = writes.write("one:pending:abc", PendingMutation.PlaylistCreate("mix"))
        assertEquals(WriteResult(WriteOutcome.SUCCESS, "one:real-1"), result)
        assertTrue(queue.rows.isEmpty())
        assertEquals(listOf("one:real-1"), reconciled.toList())
    }

    @Test
    fun deletingAPlaylistDropsTheEditsStillQueuedAgainstItAndQueuesTheDeleteIfItFailed() = runBlocking<Unit> {
        playlist("one:p", "one:a")
        one.offline = true
        writes.write("one:p", PendingMutation.PlaylistRename("x"))
        writes.write("one:p", PendingMutation.PlaylistAddTrack("one:b"))
        writes.write("one:p", PendingMutation.PlaylistDelete)
        assertEquals(listOf("one:p" to PendingMutation.PlaylistDelete), queue.mutations(), "the rename and the add are pointless now")
        assertNull(local.names["one:p"])
    }

    @Test
    fun queueingWithoutApplyingIsForWhatThePhoneAlreadyHolds() = runBlocking<Unit> {
        writes.enqueue("one:song", favorite(true))
        assertTrue(local.starred.isEmpty())
        assertEquals(1, queue.rows.size)
        assertTrue(writes.drain())
        assertEquals(listOf("star one:song"), one.calls.toList())
    }

    // ---- a server that says no ----

    private val notFound = SubsonicApiException(70, "Playlist not found")

    @Test
    fun aWriteTheServerRefusesSaysSoAndIsQueuedInCaseTheRefusalClears() = runBlocking<Unit> {
        playlist("one:p", "one:a")
        one.answerWith = notFound
        val result = writes.write("one:p", PendingMutation.PlaylistRename("x"))
        assertEquals(WriteOutcome.REFUSED, result.outcome)
        assertEquals(1, queue.rows.size, "a 'not found' can come from a proxy or a restarting server, so it gets its tries")
    }

    @Test
    fun aRefusalThatCanClearIsQueuedLikeAnOutage() = runBlocking<Unit> {
        playlist("one:p", "one:a")
        one.answerWith = SubsonicApiException(40, "Wrong username or password")
        assertEquals(WriteOutcome.FAILED, writes.write("one:p", PendingMutation.PlaylistRename("x")).outcome)
        assertEquals(1, queue.rows.size)
        one.answerWith = JellyfinApiException(401, "Unauthorized")
        assertEquals(WriteOutcome.FAILED, writes.write("one:p", PendingMutation.PlaylistRename("y")).outcome)
        assertEquals(2, queue.rows.size)
    }

    @Test
    fun aRefusalThatClearsBeforeTheLastTryLosesNothing() = runBlocking<Unit> {
        playlist("one:p", "one:a")
        one.answerWith = notFound
        writes.write("one:p", PendingMutation.PlaylistRename("x"))
        assertFalse(writes.drain())
        one.answerWith = null
        assertTrue(writes.drain())
        assertTrue(queue.rows.isEmpty())
        assertEquals(listOf("rename one:p x"), one.calls.toList())
    }

    @Test
    fun aQueuedWriteTheServerRefusesIsDroppedAndTheLineCarriesOn() = runBlocking<Unit> {
        playlist("one:gone", "one:a")
        playlist("one:here", "one:a")
        one.offline = true
        writes.write("one:gone", PendingMutation.PlaylistRename("x"))
        writes.write("one:here", PendingMutation.PlaylistRename("y"))
        one.offline = false
        // The first was a playlist deleted elsewhere; the server refuses it, and the second must still go through.
        val refusing = object : StubMusicApi("one") {
            override suspend fun renamePlaylist(playlistId: String, name: String) {
                if (playlistId == "one:gone") throw notFound
                one.calls += "rename $playlistId $name"
            }
        }
        servers["one"] = refusing
        assertFalse(writes.drain(), "a refused row is not given up on at once")
        assertEquals(listOf("rename one:here y"), one.calls.toList(), "but it does not hold the row behind it")
        assertEquals(1, queue.rows.size)
        assertFalse(writes.drain())
        assertTrue(writes.drain(), "the third refusal in a row ends it")
        assertTrue(queue.rows.isEmpty())
        assertEquals(listOf("rename one:here y"), one.calls.toList())
    }

    @Test
    fun deletingAPlaylistMadeOfflineSendsNothingToTheServer() = runBlocking<Unit> {
        one.offline = true
        val created = writes.write("one:pending:abc", PendingMutation.PlaylistCreate("mix"))
        writes.write(created.target, PendingMutation.PlaylistAddTrack("one:a"))
        assertEquals(2, queue.rows.size)
        one.offline = false

        val result = writes.write("one:pending:abc", PendingMutation.PlaylistDelete)
        assertEquals(WriteOutcome.NOT_CONFIGURED, result.outcome)
        assertTrue(queue.rows.isEmpty(), "the create and the add went with it, and no delete was queued")
        assertTrue(one.calls.isEmpty(), "the server never heard of this playlist")
        assertNull(local.names["one:pending:abc"])
    }

    @Test
    fun anEditOfAPlaylistWhoseCreateHasNotArrivedWaitsInsteadOfNamingAnUnknownId() = runBlocking<Unit> {
        one.offline = true
        writes.write("one:pending:abc", PendingMutation.PlaylistCreate("mix"))
        one.offline = false
        // The server is back, but the create is still ahead of this edit in the line: the edit must not be sent on its own.
        val outcome = writes.write("one:pending:abc", PendingMutation.PlaylistRename("mix 2")).outcome
        assertEquals(WriteOutcome.FAILED, outcome)
        assertTrue(one.calls.isEmpty())
        assertEquals(2, queue.rows.size)
    }

    @Test
    fun aCreateTheServerRefusesTakesThePlaylistAndItsEditsOffThePhone() = runBlocking<Unit> {
        one.offline = true
        writes.write("one:pending:abc", PendingMutation.PlaylistCreate("???"))
        writes.write("one:pending:abc", PendingMutation.PlaylistAddTrack("one:a"))
        one.offline = false
        one.answerWith = SubsonicApiException(10, "Required parameter is missing")
        assertFalse(writes.drain())
        assertNotNull(local.names["one:pending:abc"], "still there while the refusal might clear")
        assertFalse(writes.drain())
        assertTrue(writes.drain())
        assertTrue(queue.rows.isEmpty())
        assertNull(local.names["one:pending:abc"], "no ghost playlist is left on the phone")
    }

    @Test
    fun rowsLeftByAnOlderVersionForAPlaylistThatWasNeverCreatedAreDroppedInsteadOfBlockingTheLine() = runBlocking<Unit> {
        // What an older version queued after deleting an offline-made playlist: a delete naming a placeholder, with its create gone.
        writes.enqueue("one:pending:old", PendingMutation.PlaylistDelete)
        writes.enqueue("one:song", favorite(true))
        assertTrue(writes.drain())
        assertTrue(queue.rows.isEmpty())
        assertEquals(listOf("star one:song"), one.calls.toList(), "the favorite behind it still went")
    }

    @Test
    fun aQueuedFavoriteStillDecodesFromTheLowercaseNameItHasAlwaysBeenStoredUnder() {
        val old = """{"type":"FAVORITE","favoriteType":"album","favorite":true}"""
        assertEquals(PendingMutation.Favorite(FavoriteKind.ALBUM, true), Json.decodeFromString<PendingMutation>(old))
        assertEquals("""{"type":"FAVORITE","favoriteType":"track","favorite":false}""", Json.encodeToString<PendingMutation>(PendingMutation.Favorite(FavoriteKind.TRACK, false)))
    }

    // ---- Phone Only ----

    @Test
    fun aPhoneOnlyPlaylistIsMadeAndEditedOnThePhoneAloneAndNothingIsSentOrQueued() = runBlocking<Unit> {
        val id = local.createPhonePlaylist("road", listOf("one:a", "two:b"))
        assertTrue(ServerScope.isPhone(id))
        assertEquals("road", local.names[id])
        assertEquals(listOf("one:a", "two:b"), local.members[id], "it holds songs from any server")

        val added = writes.write(id, PendingMutation.PlaylistAddTrack("one:c"))
        val renamed = writes.write(id, PendingMutation.PlaylistRename("long road"))
        assertEquals(WriteOutcome.NOT_CONFIGURED, added.outcome)
        assertEquals(WriteOutcome.NOT_CONFIGURED, renamed.outcome)
        assertEquals(listOf("one:a", "two:b", "one:c"), local.members[id])
        assertEquals("long road", local.names[id])
        assertTrue(queue.rows.isEmpty() && one.calls.isEmpty() && two.calls.isEmpty(), "no server heard of it")
    }

    @Test
    fun anEmptyPhoneOnlyPlaylistIsMadeWithNoSongs() = runBlocking<Unit> {
        val id = local.createPhonePlaylist("empty")
        assertEquals(emptyList(), local.members[id])
        assertEquals("empty", local.names[id])
    }
}
