package com.musicplus.app.data

import com.musicplus.app.data.playback.StreamCache
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How many downloaded songs a server has on the phone, and how much space they take. */
data class DownloadSummary(val songs: Int, val bytes: Long)

/**
 * Removes a server, and deletes what it left on the phone. Runs on the app's own scope, never a screen's: closing the
 * Servers screen half way through must not leave half a library behind.
 *
 * Removing forgets the login first (so nothing can refresh the server or write its rows back), drops its edits that
 * were still waiting to sync, drops its upcoming songs from the queue (never the one playing), and deletes its cached
 * library, art, lyrics and streams. Its downloaded songs are either deleted too, or kept: kept songs stay listed and
 * playable, with just the rows they need (each song, its album and artist), under a [RemovedServer] record that
 * a new login for the same address and username picks up again.
 */
class ServerRemoval(
    private val scope: CoroutineScope,
    private val lightContext: SealedLightContext,
    private val serverConfigRepository: ServerConfigRepository,
    private val serverSyncStatus: ServerSyncStatus,
    private val capabilityRegistry: CapabilityRegistry,
    private val reachability: ServerReachability,
    private val apiHolder: ApiHolder,
    private val playbackStateRepository: PlaybackStateRepository,
    private val downloadRepository: DownloadRepository,
    private val serverCleanupDao: ServerCleanupDao,
    private val downloadDao: DownloadDao,
    private val pendingMutationDao: PendingMutationDao,
    private val queueDao: QueueDao,
    private val filesDir: File,
    private val streamCache: StreamCache,
) {
    /** Finished downloads per server, live. Sizes come off the disk, so this runs off the main thread. */
    val downloadSummaries: Flow<Map<String, DownloadSummary>> = downloadDao.observeAll().map { rows ->
        withContext(Dispatchers.IO) {
            rows.filter { it.status == DownloadStatus.COMPLETE }
                .groupBy { ServerScope.serverOf(it.songId) }
                .mapNotNull { (server, list) ->
                    server?.let { it to DownloadSummary(list.size, list.sumOf { row -> row.localFilePath?.let { path -> File(path).length() } ?: 0L }) }
                }
                .toMap()
        }
    }

    /** Removes [serverId]. With [keepDownloads] its finished downloads stay on the phone (if it has any). */
    fun remove(serverId: String, keepDownloads: Boolean): Job = scope.launch {
        AppLogger.d(TAG, "removing server $serverId (keep downloads: $keepDownloads)")
        val profile = serverConfigRepository.servers.first().find { it.id == serverId }
        val finished = downloadDao.getForServer(serverId).filter { it.status == DownloadStatus.COMPLETE }
        val keep = keepDownloads && finished.isNotEmpty() && profile != null
        val keptCount = if (keep) finished.size else 0

        serverConfigRepository.remove(
            serverId,
            keptDownloadsAs = if (keep && profile != null) RemovedServer(serverId, profile.name, profile.baseUrl, profile.username) else null,
        )
        // Ahead of the DataStore mirror: a refresh still in flight checks this before it writes a row back.
        AppServerPrefs.servers.set(AppServerPrefs.servers.value.value.filterNot { it.id == serverId })
        AppGraph.serversChanged(serverConfigRepository.activeServerId.first(), serverConfigRepository.enabledServerIds.first())
        apiHolder.forget(serverId)
        serverSyncStatus.forget(serverId)
        capabilityRegistry.forget(serverId)
        reachability.forget(serverId)

        pendingMutationDao.deleteForServer(serverId)
        dropFromQueue(serverId)

        // A download that has not finished cannot finish now; finished ones go unless they are being kept.
        for (row in downloadDao.getForServer(serverId)) {
            if (!keep || row.status != DownloadStatus.COMPLETE) downloadRepository.cancel(lightContext, row.songId)
        }
        serverCleanupDao.pruneServer(serverId, keepDownloads = keep)
        deleteCachedFiles(serverId, includingArtAndLyrics = !keep)
        // A refresh that had already passed its check when the server was removed can still land one write.
        delay(SECOND_SWEEP_DELAY_MS)
        serverCleanupDao.pruneServer(serverId, keepDownloads = keep)
        AppLogger.d(TAG, "removed server $serverId: kept $keptCount downloaded songs")
    }

    /** Deletes the downloads a removed server left, and its record. */
    fun deleteKeptDownloads(serverId: String): Job = scope.launch {
        AppLogger.d(TAG, "deleting the kept downloads of removed server $serverId")
        // Their files are about to go, so upcoming songs from this server can no longer play either.
        dropFromQueue(serverId)
        for (row in downloadDao.getForServer(serverId)) downloadRepository.cancel(lightContext, row.songId)
        serverCleanupDao.pruneServer(serverId, keepDownloads = false)
        deleteCachedFiles(serverId, includingArtAndLyrics = true)
        serverConfigRepository.forgetRemoved(serverId)
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

    /** A server's files are named after the scoped id, so they are the ones starting with its (file-safe) scope; the stream cache knows its own. */
    private suspend fun deleteCachedFiles(serverId: String, includingArtAndLyrics: Boolean) = withContext(Dispatchers.IO) {
        streamCache.deleteForServer(serverId)
        if (includingArtAndLyrics) {
            val prefix = ServerScope.fileKey(ServerScope.scope(serverId, ""))
            for (dir in listOf("albumart", "lyrics")) File(filesDir, dir).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }

    private companion object {
        const val TAG = "ServerRemoval"
        const val SECOND_SWEEP_DELAY_MS = 3_000L
    }
}
