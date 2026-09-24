package com.musicplus.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cover art as bytes, through the store's interface: a scoped id and a size in, bytes out, for both id shapes and however many servers. */
class CoverArtStoreTest {
    private val root: File = Files.createTempDirectory("coverart-test").toFile()
    private val dir = File(root, "albumart")

    /** A server as far as art is concerned: it answers by the id its own adapter would put on the wire, and remembers being asked. */
    private class FakeServer(private val pictures: Map<String, ByteArray>) : CoverArtSource {
        val asked = CopyOnWriteArrayList<Pair<String, Int>>()
        var failWith: Exception? = null
        var delayMs = 0L

        override suspend fun coverArtBytes(coverArtId: String, size: Int): ByteArray {
            asked += coverArtId to size
            if (delayMs > 0) delay(delayMs)
            failWith?.let { throw it }
            return pictures[coverArtId] ?: throw IOException("no art for $coverArtId")
        }
    }

    // Two servers with different id shapes: a Subsonic one ("mf-..." / "al-...") and a Jellyfin one (32 hex characters).
    private val subsonic = FakeServer(mapOf("sub:al-1" to bytes(10, 1), "sub:mf-2" to bytes(10, 2)))
    private val jellyfin = FakeServer(mapOf("jf:0a1b2c3d4e5f60718293a4b5c6d7e8f9" to bytes(12, 3)))
    private val servers = mapOf("sub" to subsonic, "jf" to jellyfin)
    private var unreachable = emptySet<String>()
    private var now = 1_000_000_000L

    private fun store(recheckAfterMs: Long = 0, timeoutMs: Long = 500) = CoverArtStore(
        dir = dir,
        sourceFor = { id -> ServerScope.serverOf(id)?.let(servers::get) },
        serverUnreachable = { it in unreachable },
        nowMs = { now },
        recheckAfterMs = recheckAfterMs,
        recheckTimeoutMs = timeoutMs,
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun bytes(count: Int, seed: Int) = ByteArray(count) { (seed + it).toByte() }

    @Test
    fun aSubsonicIdAndAJellyfinIdBothComeBackAsBytes() = runBlocking<Unit> {
        val store = store()
        assertContentEquals(bytes(10, 1), store.bytes("sub:al-1", 300))
        assertContentEquals(bytes(12, 3), store.bytes("jf:0a1b2c3d4e5f60718293a4b5c6d7e8f9", 300))
    }

    /** It is the scoped id that goes to the adapter (which unscopes it for the wire) and the size asked for. */
    @Test
    fun theAdapterIsAskedForTheScopedIdAndTheSize() = runBlocking<Unit> {
        store().bytes("sub:al-1", 300)
        assertEquals(listOf("sub:al-1" to 300), subsonic.asked.toList())
        assertTrue(jellyfin.asked.isEmpty(), "only the server that owns the id is asked")
    }

    @Test
    fun anIdOfAnotherServerIsNotConfusedWithTheSameNativeId() = runBlocking<Unit> {
        val other = FakeServer(mapOf("two:al-1" to bytes(10, 9)))
        val store = CoverArtStore(dir, { id -> if (ServerScope.serverOf(id) == "two") other else subsonic }, { false })
        assertContentEquals(bytes(10, 1), store.bytes("sub:al-1", 300))
        assertContentEquals(bytes(10, 9), store.bytes("two:al-1", 300))
    }

    @Test
    fun theSecondAskIsAnsweredFromDiskAndAtAnotherSizeAsksAgain() = runBlocking<Unit> {
        val store = store()
        store.bytes("sub:al-1", 300)
        store.bytes("sub:al-1", 300)
        assertEquals(1, subsonic.asked.size)
        store.bytes("sub:al-1", 600)
        assertEquals(2, subsonic.asked.size)
        assertEquals(listOf("sub_al-1-300.art", "sub_al-1-600.art"), dir.list()!!.sorted())
    }

    @Test
    fun aServerThatIsNotSetUpOrCannotAnswerIsAnErrorAndLeavesNothingOnDisk() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { store().bytes("gone:al-1", 300) }
        subsonic.failWith = IOException("connection reset")
        assertFailsWith<IOException> { store().bytes("sub:al-1", 300) }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun deletingAServersArtLeavesTheOthers() = runBlocking<Unit> {
        val store = store()
        store.bytes("sub:al-1", 300); store.bytes("sub:mf-2", 300); store.bytes("jf:0a1b2c3d4e5f60718293a4b5c6d7e8f9", 300)
        store.deleteForServer("sub")
        assertEquals(listOf("jf_0a1b2c3d4e5f60718293a4b5c6d7e8f9-300.art"), dir.list()!!.toList())
        store.clear()
        assertTrue(dir.list()!!.isEmpty())
    }

    // ---- the server's stock "no artwork" picture ----

    /** Two entries holding the same picture to the byte are the server's stock picture. Returns the store made over them. */
    private fun withStockPicture(): CoverArtStore {
        dir.mkdirs()
        val stock = bytes(20, 7)
        File(dir, "sub_al-1-300.art").writeBytes(stock)
        File(dir, "sub_mf-2-300.art").writeBytes(stock)
        File(dir, "sub_al-1-300.art").setLastModified(now - 13 * 60 * 60 * 1000)
        return store(recheckAfterMs = 12 * 60 * 60 * 1000)
    }

    @Test
    fun aPictureThatSeveralEntriesShareIsAskedAboutAgainAndTheRealOneReplacesIt() = runBlocking<Unit> {
        val store = withStockPicture()
        val fresh = store.refreshedIfStock("sub:al-1", 300)
        assertContentEquals(bytes(10, 1), fresh)
        assertContentEquals(bytes(10, 1), File(dir, "sub_al-1-300.art").readBytes(), "kept on disk")
    }

    @Test
    fun anEntryIsAskedAboutAtMostOncePerProcess() = runBlocking<Unit> {
        val store = withStockPicture()
        store.refreshedIfStock("sub:al-1", 300)
        assertNull(store.refreshedIfStock("sub:al-1", 300))
        assertEquals(1, subsonic.asked.size)
    }

    @Test
    fun aPictureThatIsNotSharedIsNotAskedAbout() = runBlocking<Unit> {
        val store = store()
        store.bytes("sub:al-1", 300)
        store.bytes("sub:mf-2", 300)
        assertNull(store.refreshedIfStock("sub:al-1", 300), "two different pictures")
        assertEquals(2, subsonic.asked.size)
    }

    @Test
    fun aRecentAnswerIsTrusted() = runBlocking<Unit> {
        dir.mkdirs()
        val stock = bytes(20, 7)
        File(dir, "sub_al-1-300.art").writeBytes(stock)
        File(dir, "sub_mf-2-300.art").writeBytes(stock)
        val store = store(recheckAfterMs = 12 * 60 * 60 * 1000)
        assertNull(store.refreshedIfStock("sub:al-1", 300), "asked less than 12 hours ago, as far as the file's age says")
        assertTrue(subsonic.asked.isEmpty())
    }

    @Test
    fun aServerThatIsDownIsLeftAlone() = runBlocking<Unit> {
        val store = withStockPicture()
        unreachable = setOf("sub")
        assertNull(store.refreshedIfStock("sub:al-1", 300))
        assertTrue(subsonic.asked.isEmpty())
    }

    @Test
    fun afterTwoUnansweredChecksNoneAreTriedAgain() = runBlocking<Unit> {
        dir.mkdirs()
        val stock = bytes(20, 7)
        for (name in listOf("a", "b", "c", "d")) File(dir, "sub_$name-300.art").apply { writeBytes(stock); setLastModified(now - 13 * 60 * 60 * 1000) }
        subsonic.failWith = IOException("down")
        val store = store(recheckAfterMs = 12 * 60 * 60 * 1000)
        listOf("sub:a", "sub:b", "sub:c", "sub:d").forEach { assertNull(store.refreshedIfStock(it, 300)) }
        assertEquals(2, subsonic.asked.size, "the third and fourth were never asked")
    }

    @Test
    fun callersArrivingMidCheckWaitForTheOneAlreadyRunning() = runBlocking<Unit> {
        val store = withStockPicture()
        subsonic.delayMs = 80
        val answers = List(4) { async { store.refreshedIfStock("sub:al-1", 300) } }.awaitAll()
        assertEquals(1, subsonic.asked.size)
        assertTrue(answers.all { it != null && it.contentEquals(bytes(10, 1)) })
    }
}
