package com.musicplus.app.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each kind of kept file forgetting a server's, or everything, through the one interface: temp directories, nothing else. */
class KeptFilesTest {
    private val root: File = Files.createTempDirectory("keptfiles-test").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun serverFile(dir: File, serverId: String, song: String, ext: String): File =
        File(dir, "${ServerScope.fileKey(ServerScope.scope(serverId, song))}.$ext").apply { parentFile.mkdirs(); writeText("x") }

    @Test
    fun lyricsAreKeptAndReadBackAndForgottenPerServer() {
        val store = LyricsStore(File(root, "lyrics"))
        store.write("one:a", """{"kind":"none"}""")
        store.write("two:b", """{"kind":"none"}""")
        assertEquals("""{"kind":"none"}""", store.read("one:a"))
        assertNull(store.read("one:missing"))

        store.deleteForServer("one")
        assertNull(store.read("one:a"), "server one's lyrics are gone")
        assertEquals("""{"kind":"none"}""", store.read("two:b"), "server two's are not")

        store.clear()
        assertNull(store.read("two:b"))
    }

    @Test
    fun aServerPrefixDoesNotMatchAnotherServerThatStartsTheSame() {
        val store = LyricsStore(File(root, "lyrics"))
        store.write("one:a", "keep me not")
        store.write("one2:a", "keep me")
        store.deleteForServer("one")
        assertNull(store.read("one:a"))
        assertEquals("keep me", store.read("one2:a"), "'one2' is another server, not a longer 'one'")
    }

    @Test
    fun downloadedFilesAreForgottenPerServerOrAllAtOnce() {
        val dir = File(root, DownloadRepository.DIRECTORY)
        val downloads = DownloadRepository(
            downloadDao = FakeDownloadDaoForFiles(),
            scheduler = object : DownloadScheduler {
                override suspend fun schedule(songId: String) {}
                override suspend fun cancel(songId: String) {}
            },
            sources = object : DownloadSources {
                override suspend fun track(songId: String): DownloadTrack? = null
                override suspend fun api(songId: String): MusicApi? = null
                override suspend fun maxBitRateKbps(): Int? = null
            },
            gate = object : DownloadGate {
                override suspend fun run(songId: String, retried: Boolean, transcoding: Boolean, block: suspend (FetchGate.Lease?) -> Unit) = false
            },
            downloadsDir = dir,
        )
        val a = serverFile(dir, "one", "s1", "flac")
        val b = serverFile(dir, "two", "s2", "mp3")
        downloads.deleteForServer("one")
        assertFalse(a.exists())
        assertTrue(b.exists())
        downloads.clear()
        assertFalse(dir.exists())
    }
}

/** A table that is never used: the files side of the download module needs none. */
private class FakeDownloadDaoForFiles : DownloadDao {
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DownloadEntity>())
    override suspend fun getBySongId(songId: String): DownloadEntity? = null
    override fun observeBySongId(songId: String) = kotlinx.coroutines.flow.flowOf<DownloadEntity?>(null)
    override suspend fun getForServer(serverId: String) = emptyList<DownloadEntity>()
    override suspend fun getByStatus(status: DownloadStatus) = emptyList<DownloadEntity>()
    override suspend fun upsert(download: DownloadEntity) {}
    override suspend fun delete(songId: String) {}
    override suspend fun deleteAll() {}
}
