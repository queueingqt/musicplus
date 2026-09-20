package com.musicplus.app.data

import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * One-time cleanup of audio files that an earlier version stored cut short.
 *
 * Until the HTTP client's 15 s whole-request cap was removed (see
 * [newJsonHttpClient]), a song that took longer than that to transfer was
 * silently truncated and then recorded as finished: the download table said
 * COMPLETE, the album showed the "downloaded" arrow, and playing it produced a
 * few seconds of sound and then silence while the timeline carried on
 * (2026-09-19: 25 of 39 downloaded FLACs on one phone, 14 of them from a single
 * album, at 4-40 % of their real size). The same cut hit the play-from-server
 * cache. The client no longer produces such files; this removes the ones that
 * already exist.
 *
 * - **Downloads:** each COMPLETE original-format download is compared with the
 *   byte length the server reports for the song. A mismatch — or a file that is
 *   gone — deletes the file. On Wi-Fi the song then goes back in the download
 *   queue; on cellular it is simply no longer marked downloaded, and the person
 *   downloads it again when they choose — re-fetching a few hundred MB of FLAC
 *   unasked over a data plan is not this cleanup's call. Downloads made at a
 *   capped quality are transcodes of a different length, so there is nothing to
 *   compare and they are left alone.
 * - **Play-from-server cache:** these are transcodes at a fixed bitrate, so a
 *   lossless original's copy that is well under a full transcode's length is a
 *   cut-off one and is dropped (it is only a cache; playing the song fetches it
 *   again). Leftover `.part` files from downloads that were killed go too.
 */
class MediaIntegrity(
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val filesDir: File,
) {
    companion object {
        /** Bump to run the cleanup again on every install that already ran an older one. */
        const val VERSION = 1

        private val LOSSLESS = setOf("flac", "wav", "alac", "aiff", "aif", "ape", "wv")
        private val CACHE_NAME = Regex("^(.+)-(\\d+)\\.mp3$")

        /** A cached copy under this fraction of a full CBR transcode counts as cut short. */
        private const val CUT_SHORT_FRACTION = 0.9

        /** A `.part` younger than this may belong to a download running right now. */
        private const val STALE_PART_MS = 2 * 60 * 1000L
    }

    /**
     * Returns true when every check got an answer, so the cleanup need not run
     * again; false when the server couldn't be asked about some song (offline,
     * error), so it should be tried again next launch.
     */
    suspend fun repair(lightContext: SealedLightContext, api: SubsonicApi, requeue: Boolean): Boolean {
        val startedAt = System.currentTimeMillis()
        var answeredAll = true
        var checked = 0
        var requeued = 0
        for (download in downloadDao.getByStatus(DownloadStatus.COMPLETE)) {
            val file = download.localFilePath?.let { File(it) }
            val track = trackDao.getById(download.songId) ?: continue
            if (file == null || !file.isFile) {
                fixUp(lightContext, download, requeue)
                requeued++
                continue
            }
            // Only an original-format file can be compared with the server's size.
            if (track.suffix == null || !file.extension.equals(track.suffix, ignoreCase = true)) continue
            val serverBytes = try {
                api.getSong(download.songId)?.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                answeredAll = false
                null
            }
            checked++
            if (serverBytes == null || file.length() == serverBytes) continue
            AppLogger.d("MediaIntegrity", "${track.title}: file is ${file.length()} of $serverBytes bytes, discarding it")
            file.delete()
            fixUp(lightContext, download, requeue)
            requeued++
        }
        val droppedCache = dropCutShortStreamCache()
        AppLogger.d(
            "MediaIntegrity",
            "checked $checked downloads, ${if (requeue) "re-queued" else "un-marked (not on Wi-Fi)"} $requeued, dropped $droppedCache cut-short cached streams " +
                "(${if (answeredAll) "complete" else "incomplete, will retry"}, ${System.currentTimeMillis() - startedAt} ms)",
        )
        return answeredAll
    }

    private suspend fun fixUp(lightContext: SealedLightContext, download: DownloadEntity, requeue: Boolean) {
        if (!requeue) {
            downloadDao.delete(download.songId)
            return
        }
        downloadDao.upsert(
            download.copy(
                localFilePath = null,
                status = DownloadStatus.QUEUED,
                queuedAtEpochMs = System.currentTimeMillis(),
                completedAtEpochMs = null,
                attemptCount = 0,
            ),
        )
        LightWork.enqueue(
            lightContext = lightContext,
            jobKey = DownloadRepository.JOB_KEY,
            inputData = mapOf("songId" to download.songId),
            tag = download.songId,
        )
    }

    private suspend fun dropCutShortStreamCache(): Int {
        val files = File(filesDir, "streamcache").listFiles() ?: return 0
        var dropped = 0
        for (file in files) {
            if (file.name.endsWith(".part")) {
                if (System.currentTimeMillis() - file.lastModified() > STALE_PART_MS) file.delete()
                continue
            }
            val match = CACHE_NAME.matchEntire(file.name) ?: continue
            val (id, capKbps) = match.destructured
            val track = trackDao.getById(id) ?: continue
            if (track.suffix?.lowercase() !in LOSSLESS) continue
            // kbps -> bytes per second is x125; a lossless original transcodes to constant bitrate.
            val fullTranscodeBytes = capKbps.toLong() * 125L * track.durationSec
            if (fullTranscodeBytes > 0 && file.length() < fullTranscodeBytes * CUT_SHORT_FRACTION) {
                file.delete()
                dropped++
            }
        }
        return dropped
    }
}
