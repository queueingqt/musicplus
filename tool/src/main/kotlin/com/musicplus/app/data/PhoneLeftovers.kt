package com.musicplus.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a removed server leaves on the phone, and the deleting of it: Room's rows, the queue, downloads, and the files of art, lyrics and
 * streams. The one [ServerLeftovers] there is outside a test; [ServerLifecycle] decides when.
 */
class PhoneLeftovers(
    private val playbackStateRepository: PlaybackStateRepository,
    private val downloadRepository: DownloadRepository,
    private val serverCleanupDao: ServerCleanupDao,
    private val downloadDao: DownloadDao,
    private val pendingMutationDao: PendingMutationDao,
    private val queueDao: QueueDao,
    /** Copies kept from streaming: always forgotten with a server (nothing plays from them without it). */
    private val streamCopies: KeptFiles,
    /** What a server leaves besides its songs' audio (art, lyrics): forgotten unless its downloads are being kept. */
    private val withTheServer: List<KeptFiles>,
) : ServerLeftovers {
    /** Sizes come off the disk, so this runs off the main thread. */
    override val downloadSummaries: Flow<Map<String, DownloadSummary>> = downloadDao.observeAll().map { rows ->
        withContext(Dispatchers.IO) {
            rows.filter { it.status == DownloadStatus.COMPLETE }
                .groupBy { ServerScope.serverOf(it.songId) }
                .mapNotNull { (server, list) ->
                    server?.let { it to DownloadSummary(list.size, list.sumOf { row -> row.localFilePath?.let { path -> File(path).length() } ?: 0L }) }
                }
                .toMap()
        }
    }

    override suspend fun finishedDownloads(serverId: String): Int =
        downloadDao.getForServer(serverId).count { it.status == DownloadStatus.COMPLETE }

    override suspend fun clear(serverId: String, keepDownloads: Boolean) {
        pendingMutationDao.deleteForServer(serverId)
        dropFromQueue(serverId)
        // A download that has not finished cannot finish now; finished ones go unless they are being kept.
        for (row in downloadDao.getForServer(serverId)) {
            if (!keepDownloads || row.status != DownloadStatus.COMPLETE) downloadRepository.cancel(row.songId)
        }
        serverCleanupDao.pruneServer(serverId, keepDownloads = keepDownloads)
        deleteCachedFiles(serverId, includingArtAndLyrics = !keepDownloads)
    }

    override suspend fun clearKept(serverId: String) {
        // Their files are about to go, so upcoming songs from this server can no longer play either.
        dropFromQueue(serverId)
        for (row in downloadDao.getForServer(serverId)) downloadRepository.cancel(row.songId)
        serverCleanupDao.pruneServer(serverId, keepDownloads = false)
        deleteCachedFiles(serverId, includingArtAndLyrics = true)
    }

    private suspend fun dropFromQueue(serverId: String) {
        val playback = PlaybackRepositoryHolder.peek()
        if (playback != null) {
            playback.removeServerFromQueue(serverId)
        } else {
            // No player in this process yet: trim what would be restored.
            queueDao.deleteAfter(serverId, playbackStateRepository.read().currentIndex)
        }
    }

    /** Each kind of kept file knows how to forget a server's. */
    private suspend fun deleteCachedFiles(serverId: String, includingArtAndLyrics: Boolean) = withContext(Dispatchers.IO) {
        streamCopies.deleteForServer(serverId)
        if (includingArtAndLyrics) withTheServer.forEach { it.deleteForServer(serverId) }
    }
}
