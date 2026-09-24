package com.musicplus.app.data

import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CancellationException
import com.musicplus.app.data.playback.StreamCache
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
 * - **Play-from-server cache:** the same cut hit those copies, but they cannot be told from a finished transcode by length (a
 *   transcode has no length of its own), and the cause is gone, so nothing is guessed about them; only the leftover `.part`
 *   files of fetches that were killed are swept.
 */
class MediaIntegrity(
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val streamCache: StreamCache,
) {
    companion object {
        /** Bump to run the cleanup again on every install that already ran an older one. */
        const val VERSION = 1


        /** A `.part` younger than this may belong to a download running right now. */
        private const val STALE_PART_MS = 2 * 60 * 1000L
    }

    /**
     * Returns true when every check got an answer, so the cleanup need not run
     * again; false when the server couldn't be asked about some song (offline,
     * error), so it should be tried again next launch.
     */
    suspend fun repair(lightContext: SealedLightContext, apiHolder: ApiHolder, requeue: Boolean): Boolean {
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
                apiHolder.forId(download.songId)?.getSong(download.songId)?.sizeBytes
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
        val sweptParts = streamCache.sweepStaleParts(STALE_PART_MS)
        AppLogger.d(
            "MediaIntegrity",
            "checked $checked downloads, ${if (requeue) "re-queued" else "un-marked (not on Wi-Fi)"} $requeued, swept $sweptParts leftover partial cache file(s) " +
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

}
