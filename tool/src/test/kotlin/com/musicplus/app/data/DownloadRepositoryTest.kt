package com.musicplus.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The download module through its interface: a fake api writing real files, an in-memory table, a recorded scheduler and a gate we control. */
class DownloadRepositoryTest {
    private val root: File = Files.createTempDirectory("downloads-test").toFile()
    private val dir = File(root, "downloads")
    private var now = 1_000L

    private class FakeDownloadDao : DownloadDao {
        val rows = MutableStateFlow<Map<String, DownloadEntity>>(emptyMap())
        override fun observeAll(): Flow<List<DownloadEntity>> = rows.map { it.values.toList() }
        override suspend fun getBySongId(songId: String) = rows.value[songId]
        override fun observeBySongId(songId: String): Flow<DownloadEntity?> = rows.map { it[songId] }
        override suspend fun getForServer(serverId: String) = rows.value.values.filter { ServerScope.serverOf(it.songId) == serverId }
        override suspend fun getByStatus(status: DownloadStatus) = rows.value.values.filter { it.status == status }
        override suspend fun upsert(download: DownloadEntity) { rows.value = rows.value + (download.songId to download) }
        override suspend fun delete(songId: String) { rows.value = rows.value - songId }
        override suspend fun deleteAll() { rows.value = emptyMap() }
    }

    private class FakeScheduler : DownloadScheduler {
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override suspend fun schedule(songId: String) { scheduled += songId }
        override suspend fun cancel(songId: String) { cancelled += songId }
    }

    private class FakeGate : DownloadGate {
        var slot = true
        var congestion = 0
        val retriedFlags = mutableListOf<Boolean>()
        override suspend fun run(songId: String, retried: Boolean, transcoding: Boolean, block: suspend (FetchGate.Lease?) -> Unit): Boolean {
            retriedFlags += retried
            if (!slot) return false
            block(null)
            return true
        }
        override fun onCongestion() { congestion++ }
    }

    private class FakeApi : StubMusicApi("srv") {
        var failWith: Exception? = null
        val calls = mutableListOf<String>()
        override suspend fun downloadToFile(songId: String, destination: File, lease: FetchGate.Lease?) {
            calls += "download"
            destination.writeText("partial")
            failWith?.let { throw it }
            destination.writeText("whole song")
        }
        override suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int?, lease: FetchGate.Lease?) {
            calls += "stream@$maxBitRateKbps"
            destination.writeText("transcoded")
        }
    }

    private class FakeSources(val api: FakeApi?) : DownloadSources {
        var track: DownloadTrack? = DownloadTrack("flac")
        var cap: Int? = null
        override suspend fun track(songId: String) = track
        override suspend fun api(songId: String): MusicApi? = api
        override suspend fun maxBitRateKbps() = cap
    }

    private val dao = FakeDownloadDao()
    private val scheduler = FakeScheduler()
    private val gate = FakeGate()
    private val api = FakeApi()
    private val sources = FakeSources(api)
    private val song = "srv:song1"
    private val downloads = DownloadRepository(dao, scheduler, sources, gate, dir) { now }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun expectedFile(ext: String) = File(dir, "${ServerScope.fileKey(song)}.$ext")

    @Test
    fun enqueueWritesAQueuedRowAndSchedulesTheJob() = runBlocking {
        downloads.requeue(song)
        val row = dao.getBySongId(song)!!
        assertEquals(DownloadStatus.QUEUED, row.status)
        assertEquals(0, row.attemptCount)
        assertEquals(listOf(song), scheduler.scheduled)
    }

    @Test
    fun aSuccessfulRunLeavesTheWholeFileAndACompleteRow() = runBlocking {
        val outcome = downloads.run(song)
        assertEquals(DownloadOutcome.Done, outcome)
        assertEquals("whole song", expectedFile("flac").readText())
        val row = dao.getBySongId(song)!!
        assertEquals(DownloadStatus.COMPLETE, row.status)
        assertEquals(expectedFile("flac").absolutePath, row.localFilePath)
        assertEquals(1_000L, row.completedAtEpochMs)
    }

    @Test
    fun aQualityCapStreamsAndSavesAsMp3WhateverTheSourceIs() = runBlocking {
        sources.cap = 128
        downloads.run(song)
        assertEquals(listOf("stream@128"), api.calls)
        assertEquals("transcoded", expectedFile("mp3").readText())
        assertFalse(expectedFile("flac").exists())
    }

    @Test
    fun noSlotInTimeIsARetryThatDoesNotCountAsAFailedAttempt() = runBlocking {
        gate.slot = false
        assertEquals(DownloadOutcome.Retry, downloads.run(song))
        assertNull(dao.getBySongId(song), "nothing was written")
    }

    @Test
    fun aFailureRetriesUntilTheAttemptsRunOutThenGivesUp() = runBlocking {
        api.failWith = IOException("cut short: got 5 of 100 bytes")
        assertEquals(DownloadOutcome.Retry, downloads.run(song))
        assertEquals(1, dao.getBySongId(song)!!.attemptCount)
        assertEquals(DownloadStatus.QUEUED, dao.getBySongId(song)!!.status)
        assertFalse(expectedFile("flac").exists(), "the half-written file is removed")

        assertEquals(DownloadOutcome.Retry, downloads.run(song))
        assertEquals(2, dao.getBySongId(song)!!.attemptCount)

        val last = downloads.run(song)
        assertIs<DownloadOutcome.Failed>(last)
        assertEquals(DownloadStatus.FAILED, dao.getBySongId(song)!!.status)
        assertEquals(DownloadRepository.MAX_DOWNLOAD_ATTEMPTS, dao.getBySongId(song)!!.attemptCount)
        assertEquals(listOf(false, true, true), gate.retriedFlags, "a song that failed before is ranked as a retry")
    }

    @Test
    fun aDroppedConnectionBacksTheGateOffButAServerSayingNoDoesNot() = runBlocking {
        api.failWith = IOException("cut short: got 5 of 100 bytes")
        downloads.run(song)
        assertEquals(1, gate.congestion)

        api.failWith = ServerRefusedException("server answered 404 Not Found")
        downloads.run(song)
        assertEquals(1, gate.congestion, "a 404 says nothing about the link")
    }

    @Test
    fun aSongWithoutARowOrServerFailsAtOnce() = runBlocking {
        sources.track = null
        assertIs<DownloadOutcome.Failed>(downloads.run(song))
        sources.track = DownloadTrack("flac")
        val noServer = DownloadRepository(dao, scheduler, FakeSources(null), gate, dir) { now }
        assertIs<DownloadOutcome.Failed>(noServer.run(song))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun cancellationRemovesThePartialFileAndIsNotAFailedAttempt() = runBlocking {
        api.failWith = CancellationException("stopped")
        var thrown = false
        try { downloads.run(song) } catch (e: CancellationException) { thrown = true }
        assertTrue(thrown)
        assertFalse(expectedFile("flac").exists())
        assertNull(dao.getBySongId(song))
    }

    @Test
    fun cancelStopsTheJobDeletesTheRecordedFileAndDropsTheRow() = runBlocking {
        downloads.run(song)
        assertTrue(expectedFile("flac").exists())
        downloads.cancel(song)
        assertEquals(listOf(song), scheduler.cancelled)
        assertFalse(expectedFile("flac").exists())
        assertNull(dao.getBySongId(song))
    }

    @Test
    fun retryFailedRearmsOnlyTheFailedOnesWithFreshAttempts() = runBlocking {
        dao.upsert(DownloadEntity("srv:a", null, DownloadStatus.FAILED, 1, null, attemptCount = 3))
        dao.upsert(DownloadEntity("srv:b", "/x", DownloadStatus.COMPLETE, 1, 2, attemptCount = 0))
        downloads.retryFailed()
        assertEquals(listOf("srv:a"), scheduler.scheduled)
        assertEquals(DownloadStatus.QUEUED, dao.getBySongId("srv:a")!!.status)
        assertEquals(0, dao.getBySongId("srv:a")!!.attemptCount)
        assertEquals(DownloadStatus.COMPLETE, dao.getBySongId("srv:b")!!.status)
    }

    @Test
    fun onlyAFileInTheSongsOwnFormatCanBeComparedWithTheServersLength() {
        assertTrue(DownloadRepository.comparableWithOriginal(File("a.flac"), "flac"))
        assertFalse(DownloadRepository.comparableWithOriginal(File("a.mp3"), "flac"), "a capped download of a flac is a transcode")
        assertFalse(DownloadRepository.comparableWithOriginal(File("a.mp3"), "mp3"), "an mp3 source cannot be told from its transcode")
        assertFalse(DownloadRepository.comparableWithOriginal(File("a.flac"), null))
    }
}
