package com.musicplus.app.data.playback

import com.musicplus.app.data.FetchGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamCacheTest {
    private val dir: File = Files.createTempDirectory("streamcache-test").toFile().resolve("streamcache")
    private val cache = StreamCache(dir, listingTtlMs = 0)
    private val rank: () -> FetchGate.Priority = { FetchGate.Priority.NOW_PLAYING }

    @AfterTest
    fun cleanUp() {
        dir.parentFile.deleteRecursively()
    }

    private fun writing(bytes: Int = 10) = suspend { part: File, _: FetchGate.Lease -> part.writeBytes(ByteArray(bytes)) }

    @Test
    fun aCopyIsNamedAfterTheScopedIdAndTheQuality() {
        assertEquals("srv_abc-192.mp3", cache.file("srv:abc", 192).name)
        assertEquals("srv_abc-orig.mp3", cache.file("srv:abc", null).name)
    }

    @Test
    fun aFetchedCopyAppearsOnlyOnceFinishedAndLeavesNoPart() = runBlocking<Unit> {
        val file = cache.fetch("srv:abc", 192, rank) { part, _ ->
            part.writeBytes(ByteArray(10))
            assertFalse(cache.has("srv:abc", 192), "not visible at its final path while still being written")
        }
        assertTrue(file.exists())
        assertEquals(10, file.length())
        assertEquals(listOf("srv_abc-192.mp3"), dir.list()!!.toList())
    }

    @Test
    fun aCopyThatIsAlreadyThereIsNotFetchedAgain() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val download = suspend { part: File, _: FetchGate.Lease -> calls.incrementAndGet(); part.writeBytes(ByteArray(4)) }
        cache.fetch("srv:abc", 192, rank, download)
        cache.fetch("srv:abc", 192, rank, download)
        assertEquals(1, calls.get())
    }

    @Test
    fun callersArrivingMidFetchShareOneDownload() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val slow = suspend { part: File, _: FetchGate.Lease -> calls.incrementAndGet(); delay(80); part.writeBytes(ByteArray(4)) }
        val files = List(4) { async { cache.fetch("srv:abc", 192, rank, slow) } }.awaitAll()
        assertEquals(1, calls.get(), "one download for four callers")
        assertTrue(files.all { it.exists() && it.length() == 4L })
    }

    @Test
    fun aFailedFetchLeavesNothingBehindAndTheErrorReachesTheCaller() = runBlocking<Unit> {
        assertFailsWith<IOException> {
            cache.fetch("srv:abc", 192, rank) { part, _ -> part.writeBytes(ByteArray(3)); throw IOException("connection reset") }
        }
        assertEquals(emptyList(), dir.list()!!.toList(), "neither a final file nor a .part")
        assertFalse(cache.has("srv:abc", 192))
    }

    @Test
    fun aFailedFetchCanBeRetriedAndThenSucceeds() = runBlocking<Unit> {
        runCatching { cache.fetch("srv:abc", 192, rank) { _, _ -> throw IOException("boom") } }
        assertTrue(cache.fetch("srv:abc", 192, rank, writing()).exists())
    }

    @Test
    fun anyCopyFindsTheSongAtAnyQuality() = runBlocking<Unit> {
        cache.fetch("srv:abc", 320, rank, writing())
        cache.refreshListingNow()
        assertEquals("srv_abc-320.mp3", cache.anyCopy("srv:abc")!!.name)
        assertNull(cache.anyCopy("srv:other"))
    }

    /** A song whose id contains a dash must not be given another song's copy. */
    @Test
    fun anyCopyDoesNotMatchAnotherSongWhoseIdStartsTheSame() = runBlocking<Unit> {
        cache.fetch("srv:ab-c", 192, rank, writing())
        cache.refreshListingNow()
        assertNull(cache.anyCopy("srv:ab"))
        assertNotNull(cache.anyCopy("srv:ab-c"))
    }

    @Test
    fun aPlaceholderAndAPartAreNotCopies() {
        dir.mkdirs()
        cache.placeholder("srv:abc").let { assertFalse(it.exists()); assertEquals("srv_abc-unreachable", it.name) }
        File(dir, "srv_abc-192.mp3.part").writeBytes(ByteArray(1))
        cache.refreshListingNow()
        assertNull(cache.anyCopy("srv:abc"))
    }

    @Test
    fun deletingAServersCopiesLeavesTheOthers() = runBlocking<Unit> {
        cache.fetch("one:a", 192, rank, writing()); cache.fetch("one:b", null, rank, writing()); cache.fetch("two:a", 192, rank, writing())
        cache.deleteForServer("one")
        assertEquals(listOf("two_a-192.mp3"), dir.list()!!.toList())
    }

    @Test
    fun aServerIdThatStartsAnotherServersIdDoesNotDeleteItsCopies() = runBlocking<Unit> {
        cache.fetch("a:x", 192, rank, writing()); cache.fetch("ab:x", 192, rank, writing())
        cache.deleteForServer("a")
        assertEquals(listOf("ab_x-192.mp3"), dir.list()!!.toList())
    }

    @Test
    fun onlyStalePartsAreSwept() {
        dir.mkdirs()
        val stale = File(dir, "one_a-192.mp3.part").apply { writeBytes(ByteArray(1)); setLastModified(System.currentTimeMillis() - 10 * 60_000) }
        val fresh = File(dir, "one_b-192.mp3.part").apply { writeBytes(ByteArray(1)) }
        val copy = File(dir, "one_c-192.mp3").apply { writeBytes(ByteArray(1)); setLastModified(System.currentTimeMillis() - 10 * 60_000) }
        assertEquals(1, cache.sweepStaleParts(2 * 60_000))
        assertFalse(stale.exists()); assertTrue(fresh.exists()); assertTrue(copy.exists(), "a finished copy is never swept")
    }

    @Test
    fun clearRemovesEverything() = runBlocking<Unit> {
        cache.fetch("srv:abc", 192, rank, writing())
        cache.clear()
        assertFalse(dir.exists())
        cache.refreshListingNow()
        assertNull(cache.anyCopy("srv:abc"))
    }
}
