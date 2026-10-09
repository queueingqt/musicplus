package com.musicplus.app.data

import com.musicplus.app.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/** What the download work needs to know about a song before fetching it. */
data class DownloadTrack(val suffix: String?, val durationSec: Int? = null)

/** How a download job is armed and stopped (WorkManager through [com.thelightphone.sdk.LightWork] on the phone, a recorder in a test). */
interface DownloadScheduler {
    suspend fun schedule(songId: String)
    suspend fun cancel(songId: String)
}

/** Where a download looks things up: the song's row, the api of the server that owns it, and the chosen download quality. */
interface DownloadSources {
    suspend fun track(songId: String): DownloadTrack?
    suspend fun api(songId: String): MusicApi?

    /** The bit-rate cap for downloads, or null for the original file. */
    suspend fun maxBitRateKbps(): Int?
}

/** The fetch scheduler a download goes through: priority order, and only as many at a time as the connection takes. */
interface DownloadGate {
    /** Runs [block] when a slot comes free; false if none did in time (a long backlog). [retried] is true for a song that already failed once. */
    suspend fun run(songId: String, retried: Boolean, transcoding: Boolean, block: suspend (FetchGate.Lease?) -> Unit): Boolean
}

/** How one run of the download job ended, in the job's own terms. */
sealed interface DownloadOutcome {
    /** The file is on disk and the row says COMPLETE. */
    data object Done : DownloadOutcome

    /** Try again later: no slot came free, or a transient failure with attempts left. */
    data object Retry : DownloadOutcome

    /** Stop: retrying will not help, or the attempts ran out (the row says FAILED in the second case). */
    data class Failed(val reason: String) : DownloadOutcome
}

/**
 * Download queue for offline listening, and the one owner of what a download is from QUEUED to gone: the row's status transitions, the file's
 * path and extension, scheduling and cancelling the job, and the work the job does ([run]). Enqueuing writes a QUEUED row at once (so the
 * Downloads screen has something to show) and hands the work to the [scheduler].
 *
 * The job handler (`downloadTrack`, in DownloadJob.kt) only has to find the song id and pass the outcome back to the SDK; everything it
 * used to decide lives here, so it is tested here with a fake api and a recorded scheduler.
 */
class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val scheduler: DownloadScheduler,
    private val sources: DownloadSources,
    private val gate: DownloadGate,
    private val downloadsDir: File,
    private val audioLength: AudioLength = AudioLength.device,
    private val clock: () -> Long = System::currentTimeMillis,
) : KeptFiles {
    companion object {
        const val JOB_KEY = "download-track"

        // A download that hit a real error (the server genuinely unreachable) used to look the same as one still in progress forever,
        // because WorkManager's own Result.retry() has no attempt ceiling. Giving up is the job's decision, and the count is kept on the
        // row since WorkManager passes none into the handler. 3 rides out a brief blip across a few backoff cycles without leaving
        // something well and truly stuck looking like "still trying" for hours.
        const val MAX_DOWNLOAD_ATTEMPTS = 3

        /** The folder under the app's files directory that holds downloads. */
        const val DIRECTORY = "downloads"

        /**
         * Whether a file can be compared with the byte length the server reports for the original song. A download at a capped quality is
         * a transcode, always saved as `.mp3`, so only a file in the song's own format qualifies, and an mp3 source cannot be told from
         * its transcode by name, so it never does.
         */
        fun comparableWithOriginal(file: File, suffix: String?): Boolean =
            suffix != null && !suffix.equals("mp3", ignoreCase = true) && file.extension.equals(suffix, ignoreCase = true)

    }

    fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    /** Every download row now, by song id: what a song's download status is joined against. */
    suspend fun byId(): Map<String, DownloadEntity> = downloadDao.observeAll().first().associateBy { it.songId }
    fun observeStatus(songId: String): Flow<DownloadEntity?> = downloadDao.observeBySongId(songId)

    suspend fun enqueue(track: Track) = requeue(track.id)

    /** Puts a song back in the queue with its attempts reset: a new download, a manual retry, or a file found cut short. */
    suspend fun requeue(songId: String) {
        downloadDao.upsert(row(songId, DownloadStatus.QUEUED, attempts = 0))
        scheduler.schedule(songId)
    }

    /**
     * Stops the job, deletes the file and drops the row. The file is read off the row's own path, never reconstructed by guessing an
     * extension: a download at "Original" quality is saved under its real source suffix, and guessing `.mp3` once left the real audio
     * orphaned on disk while the row reported it removed.
     */
    suspend fun cancel(songId: String) {
        scheduler.cancel(songId)
        downloadDao.getBySongId(songId)?.localFilePath?.let { File(it).delete() }
        downloadDao.delete(songId)
    }

    /**
     * Re-arms every download that gave up after [MAX_DOWNLOAD_ATTEMPTS], once per process start: the same "a fresh shot every time the
     * app reopens" the sync queue has. Attempts go back to 0, so a download that failed for a since-resolved reason gets three fresh
     * tries, not none.
     */
    suspend fun retryFailed() {
        for (entity in downloadDao.getByStatus(DownloadStatus.FAILED)) requeue(entity.songId)
    }

    /** The job's work: fetch [songId] into its file and record how it went. */
    suspend fun run(songId: String): DownloadOutcome {
        val track = sources.track(songId) ?: run {
            AppLogger.e(TAG, "no track row for songId=$songId")
            return DownloadOutcome.Failed("no track row for song")
        }
        // The server that owns this song, which is not necessarily the active one. Its being gone or not set up will not change by retrying.
        val api = sources.api(songId) ?: run {
            AppLogger.e(TAG, "no server for songId=$songId")
            return DownloadOutcome.Failed("no server for song")
        }
        // A capped quality means the server transcodes, which stream.view returns as mp3 whatever the source, so the extension follows the
        // cap and not the song's own suffix (the original format, wrong once transcoding happens). Null is the original file.
        val maxBitRateKbps = sources.maxBitRateKbps()
        val destination = fileFor(songId, track.suffix, maxBitRateKbps)

        // Read before the attempt: a WorkManager retry is a fresh invocation, so the count has to be persisted, not held in a local.
        val previousAttempts = downloadDao.getBySongId(songId)?.attemptCount ?: 0

        return try {
            // download.view has no maxBitRate parameter (original file only), so a transcoded download goes through stream.view, the
            // endpoint live playback uses for the same.
            val fetched = gate.run(songId, retried = previousAttempts > 0, transcoding = maxBitRateKbps != null) { lease ->
                if (maxBitRateKbps != null) api.streamToFile(songId, destination, maxBitRateKbps, lease) else api.downloadToFile(songId, destination, lease)
            }
            // No slot came free in time: try again later, and do not count it as a failed attempt.
            if (!fetched) return DownloadOutcome.Retry
            // A transcode announces no length, so a body the server ended early passes the transfer's own checks; its playing time does not.
            if (maxBitRateKbps != null) withContext(Dispatchers.IO) { CutShort.check(destination, track.durationSec, audioLength) }
            downloadDao.upsert(row(songId, DownloadStatus.COMPLETE, path = destination.absolutePath, completed = true))
            DownloadOutcome.Done
        } catch (e: CancellationException) {
            // WorkManager stopped the job (its time limit, or the work was cancelled): not a failed attempt, and nothing worth keeping of a half-written file.
            destination.delete()
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "download failed for songId=$songId", e)
            destination.delete()

            val attempt = previousAttempts + 1
            if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                downloadDao.upsert(row(songId, DownloadStatus.FAILED, attempts = attempt))
                DownloadOutcome.Failed("download failed after retries") // exhausted: stop WorkManager's own retries too, let the person retry from the UI
            } else {
                downloadDao.upsert(row(songId, DownloadStatus.QUEUED, attempts = attempt))
                DownloadOutcome.Retry // transient: let WorkManager back off and retry
            }
        }
    }

    /** Deletes the downloaded files of [serverId]'s songs. Rows are not touched: whoever removes a server settles those (see [cancel]). */
    override fun deleteForServer(serverId: String) {
        val prefix = serverFilePrefix(serverId)
        downloadsDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /** Deletes every downloaded file. The rows go with [DownloadDao.deleteAll]. */
    override fun clear() {
        downloadsDir.deleteRecursively()
    }

    /** Where a song's download lives: `downloads/<scoped key>.<ext>`, mp3 when a quality cap makes the server transcode. */
    fun fileFor(songId: String, suffix: String?, maxBitRateKbps: Int?): File {
        downloadsDir.mkdirs()
        return File(downloadsDir, "${ServerScope.fileKey(songId)}.${if (maxBitRateKbps != null) "mp3" else (suffix ?: "mp3")}")
    }

    private fun row(songId: String, status: DownloadStatus, attempts: Int = 0, path: String? = null, completed: Boolean = false) =
        DownloadEntity(
            songId = songId,
            localFilePath = path,
            status = status,
            queuedAtEpochMs = clock(),
            completedAtEpochMs = if (completed) clock() else null,
            attemptCount = attempts,
        )
}

private const val TAG = "MusicPlusDownload"
