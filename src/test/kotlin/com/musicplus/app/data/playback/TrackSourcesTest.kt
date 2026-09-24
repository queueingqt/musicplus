package com.musicplus.app.data.playback

import com.musicplus.app.Track
import com.musicplus.app.data.FetchGate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackSourcesTest {
    private val root: File = Files.createTempDirectory("tracksources-test").toFile()
    private val cache = StreamCache(File(root, "streamcache"), listingTtlMs = 0)

    /** The server, as far as a song's source is concerned: it hands out URLs and writes files, and remembers being asked. */
    private class FakeStreams(private val scheme: String = "https") : SongStreams {
        val fetched = CopyOnWriteArrayList<String>()
        override fun streamUrl(songId: String, maxBitRateKbps: Int?) = "$scheme://server/stream?id=$songId&kbps=$maxBitRateKbps"
        override suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int?, lease: FetchGate.Lease?) {
            fetched += songId
            destination.writeBytes(ByteArray(8))
        }
    }

    private var api: SongStreams? = FakeStreams()
    private var serverUsable = true
    private var kbps: Int? = 192
    private var wifi = true
    private var playerCanFetch = true
    private var kbpsAsked = 0

    private val sources = TrackSources(
        cache = cache,
        apiFor = { api },
        serverUsable = { serverUsable },
        priorityOf = { null },
        streamKbps = { kbpsAsked++; kbps },
        onWifi = { wifi },
        canStream = { playerCanFetch },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun track(id: String, localFilePath: String? = null) = Track(
        id = "srv:$id", title = id, albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = 100, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = localFilePath,
    )

    private val fetched get() = (api as FakeStreams).fetched.toList()

    private fun cachedAlready(id: String, kbps: Int? = this.kbps): File {
        val file = cache.file("srv:$id", kbps)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(8))
        cache.refreshListingNow()
        return file
    }

    // ---- the song being started ----

    @Test
    fun aDownloadThatIsThereIsPlayedFromItWhateverTheServerIsDoing() = runBlocking<Unit> {
        val download = File(root, "song.flac").apply { writeBytes(ByteArray(8)) }
        serverUsable = false
        val source = sources.resolveStart(track("a", localFilePath = download.path))
        assertEquals(SongSource.OnPhone(download), source)
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun aDownloadWhoseFileIsGoneIsTreatedAsNotDownloaded() = runBlocking<Unit> {
        val source = sources.resolveStart(track("a", localFilePath = File(root, "removed.flac").path))
        assertIs<SongSource.Streamed>(source)
    }

    @Test
    fun anUncachedSongIsStreamedSoAudioStartsAtOnceAndItsFileIsNotFetchedHere() = runBlocking<Unit> {
        val source = sources.resolveStart(track("a"))
        assertEquals(SongSource.Streamed("https://server/stream?id=srv:a&kbps=192", transcoded = true), source)
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun aTranscodedStreamCannotSeekButAnOriginalOneCan() = runBlocking<Unit> {
        assertFalse(sources.resolveStart(track("a")).seekable)
        kbps = null
        assertTrue(sources.resolveStart(track("a")).seekable)
    }

    @Test
    fun aSongAlreadyInTheCacheIsPlayedFromItNotStreamedAgain() = runBlocking<Unit> {
        val copy = cachedAlready("a")
        val source = sources.resolveStart(track("a"))
        assertEquals(SongSource.OnPhone(copy), source)
        assertTrue(source.seekable)
        assertEquals(emptyList(), fetched)
    }

    /** A copy kept at another quality is not "already have it at the quality wanted now": it is streamed and fetched afresh. */
    @Test
    fun aCopyAtAnotherQualityDoesNotCount() = runBlocking<Unit> {
        cachedAlready("a", kbps = 320)
        assertIs<SongSource.Streamed>(sources.resolveStart(track("a")))
    }

    @Test
    fun whereThePlayerCannotFetchTheUrlTheSongIsFetchedIntoTheCacheFirst() = runBlocking<Unit> {
        playerCanFetch = false
        val source = sources.resolveStart(track("a"))
        assertEquals(SongSource.OnPhone(cache.file("srv:a", 192)), source)
        assertTrue(cache.has("srv:a", 192))
        assertEquals(listOf("srv:a"), fetched)
    }

    /** The one policy for both schemes: what decides is whether the player can fetch the URL, not which scheme it has. */
    @Test
    fun httpAndHttpsAreTreatedAlike() = runBlocking<Unit> {
        api = FakeStreams(scheme = "http")
        assertEquals("http://server/stream?id=srv:a&kbps=192", (sources.resolveStart(track("a")) as SongSource.Streamed).url)
        api = FakeStreams(scheme = "https")
        assertEquals("https://server/stream?id=srv:b&kbps=192", (sources.resolveStart(track("b")) as SongSource.Streamed).url)
    }

    // ---- a song whose server is out ----

    @Test
    fun aSongWhoseServerIsOutPlaysFromAnyCopyOfItThePhoneHas() = runBlocking<Unit> {
        val copy = cachedAlready("a", kbps = 320)
        serverUsable = false
        assertEquals(SongSource.OnPhone(copy), sources.resolveStart(track("a")))
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun withNoCopyItGetsAStandInThatDoesNotExistAndNothingIsFetchedOrStreamed() = runBlocking<Unit> {
        serverUsable = false
        val source = sources.resolveStart(track("a"))
        assertIs<SongSource.OnPhone>(source)
        assertEquals(cache.placeholder("srv:a"), source.file)
        assertFalse(source.file.exists())
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun aServerThatWasRemovedFailsASongThatNeedsIt() = runBlocking<Unit> {
        api = null
        assertFailsWith<IOException> { sources.resolveStart(track("a")) }
    }

    @Test
    fun aServerThatWasRemovedStillPlaysADownloadedSong() = runBlocking<Unit> {
        api = null
        val download = File(root, "song.flac").apply { writeBytes(ByteArray(8)) }
        assertEquals(SongSource.OnPhone(download), sources.resolveStart(track("a", localFilePath = download.path)))
    }

    // ---- a whole queue ----

    private val queue get() = List(7) { track("t$it") }

    @Test
    fun onWifiEveryUncachedSongIsFetchedAheadAndNoneIsStreamed() = runBlocking<Unit> {
        val resolved = sources.resolveQueue(queue, current = 1)!!
        assertTrue(resolved.all { it is SongSource.OnPhone })
        assertEquals(7, fetched.size)
    }

    @Test
    fun offWifiOnlyThePlayingSongAndTheNextThreeAreFetchedAndTheRestStream() = runBlocking<Unit> {
        wifi = false
        val resolved = sources.resolveQueue(queue, current = 1)!!
        assertEquals(setOf("srv:t1", "srv:t2", "srv:t3", "srv:t4"), fetched.toSet())
        assertEquals(listOf(true, false, false, false, false, true, true), resolved.map { it is SongSource.Streamed })
    }

    @Test
    fun offWifiASongBeyondTheFetchedRangeThatIsCachedPlaysFromItsFile() = runBlocking<Unit> {
        wifi = false
        val copy = cachedAlready("t6")
        val resolved = sources.resolveQueue(queue, current = 0)!!
        assertEquals(SongSource.OnPhone(copy), resolved[6])
    }

    @Test
    fun offWifiWherePlayerCannotFetchTheUrlEverythingIsStillFetched() = runBlocking<Unit> {
        wifi = false
        playerCanFetch = false
        val resolved = sources.resolveQueue(queue, current = 0)!!
        assertTrue(resolved.all { it is SongSource.OnPhone })
        assertEquals(7, fetched.size)
    }

    @Test
    fun aQueueIsDecidedAgainstOneReadingOfTheQuality() = runBlocking<Unit> {
        sources.resolveQueue(queue, current = 0)
        assertEquals(1, kbpsAsked)
    }

    @Test
    fun aSupersededQueueResolvesToNothingAndFetchesNothing() = runBlocking<Unit> {
        assertNull(sources.resolveQueue(queue, current = 0) { false })
        assertEquals(emptyList(), fetched)
        assertNotNull(sources.resolveQueue(queue, current = 0) { true })
    }

    @Test
    fun aSongThatCannotBeFetchedFailsTheWholeQueue() = runBlocking<Unit> {
        api = object : SongStreams {
            override fun streamUrl(songId: String, maxBitRateKbps: Int?) = "https://server/$songId"
            override suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int?, lease: FetchGate.Lease?) {
                if (songId == "srv:t2") throw IOException("connection reset")
                destination.writeBytes(ByteArray(8))
            }
        }
        assertFailsWith<IOException> { sources.resolveQueue(queue, current = 0) }
        assertFalse(cache.has("srv:t2", 192), "a failed fetch leaves nothing in the cache")
    }
}
