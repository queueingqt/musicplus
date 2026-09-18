package com.musicplus.app.data

import com.musicplus.app.Track
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Download queue for offline listening. Enqueuing writes a QUEUED row immediately
 * (so DownloadsScreen has something to show right away) and hands the real work to
 * [LightWork] / WorkManager, which decides timing (this is background work, not
 * time-sensitive — see the SDK reference notes on LightWork's 15-minute periodic
 * floor and system-decided scheduling).
 */
class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
) {
    companion object {
        const val JOB_KEY = "download-track"
    }

    fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()
    fun observeStatus(songId: String): Flow<DownloadEntity?> = downloadDao.observeBySongId(songId)

    suspend fun enqueue(lightContext: SealedLightContext, track: Track) {
        downloadDao.upsert(
            DownloadEntity(
                songId = track.id,
                localFilePath = null,
                status = DownloadStatus.QUEUED,
                queuedAtEpochMs = System.currentTimeMillis(),
                completedAtEpochMs = null,
            ),
        )
        LightWork.enqueue(
            lightContext = lightContext,
            jobKey = JOB_KEY,
            inputData = mapOf("songId" to track.id),
            tag = track.id,
        )
    }

    suspend fun cancel(lightContext: SealedLightContext, songId: String) {
        LightWork.cancel(lightContext, jobKeyOrTag = songId)
        localFile(lightContext, songId)?.delete()
        downloadDao.delete(songId)
    }

    fun localFile(lightContext: SealedLightContext, songId: String, suffix: String = "mp3"): File? {
        val dir = File(lightContext.filesDir, "downloads")
        return File(dir, "$songId.$suffix")
    }
}

/**
 * The actual download job. Verified against the real `LightWork.kt` (not just
 * summarized notes): `@LightJob` has `SOURCE` retention, and its own doc comment
 * says it marks a "top-level `val`" — the KSP plugin scans for exactly that shape
 * at compile time to populate `LightSdkRegistry.jobs`, so this MUST be a true
 * top-level declaration, not nested in an `object` (an earlier draft nested it in
 * `object DownloadJobs`, which the processor would not have discovered).
 *
 * Builds its own network client rather than reusing `AppGraph`'s `apiHolder` —
 * WorkManager can run this in a fresh process with none of the app's in-memory
 * state warm, so it can't assume one exists. The database is the one exception
 * (see the `AppGraph.from(...)` call below): reusing the app's singleton when
 * the process IS already alive is what makes download progress actually show
 * up live instead of only after the screen is reopened.
 */
@LightJob(DownloadRepository.JOB_KEY)
val downloadTrack: LightJobHandler = handler@{ lightContext, input ->
        val tag = "MusicPlusDownload"
        // input["songId"] comes back as the literal string "songId=<value>", not
        // just "<value>" — confirmed on-device via logcat, 2026-09-17. Root cause is
        // upstream: LightWork.kt's `Data.toStringMap()` does
        // `keyValueMap.mapValues { it.toString() }`, but mapValues's lambda
        // parameter is the Map.Entry, not the value, so `it.toString()` produces
        // Java's default `"key=value"` Map.Entry format instead of the value alone.
        // This is a Light SDK bug, not something fixable from a tool's own repo —
        // worked around defensively here rather than trusting the value as-is.
        val songId = input["songId"]?.removePrefix("songId=") ?: run {
            android.util.Log.e(tag, "no songId in input: $input")
            return@handler LightJobResult.Error()
        }

        // First emitted value is enough — a job doesn't need to react to later config changes.
        val config = ServerConfigRepository(lightContext.dataStore).serverConfig.first() ?: run {
            android.util.Log.e(tag, "no server config saved")
            return@handler LightJobResult.Error() // not configured — retrying won't help
        }

        // AppGraph.from(...), not MusicPlusDatabase.create(...) directly — the
        // latter builds a brand-new Room instance every time, and Room's Flow
        // invalidation is tracked per-*instance*, not per underlying file: a
        // write through a second instance never notifies Flows the app's own
        // (already-open) instance is serving, so every download-status row
        // this job wrote was correct on disk but the in-progress action menu
        // never advanced past "Downloading" until the screen was reopened and
        // re-queried fresh. Confirmed on-device, 2026-09-18. AppGraph.from is
        // the same memoized singleton every screen already calls — reusing it
        // here means this job shares the app's live instance whenever the
        // app process is actually alive to observe it, and transparently
        // builds its own fresh one (same as before) on the rare cold-process
        // WorkManager run, since AppGraph.from()'s own singleton is per-process.
        val db = AppGraph.from(lightContext).database
        val track = db.trackDao().getById(songId) ?: run {
            android.util.Log.e(tag, "no track row for songId=$songId")
            AppLogger.e(tag, "no track row for songId=$songId")
            return@handler LightJobResult.Error()
        }

        val api = SubsonicApi(SubsonicClient(config))
        val destination = File(lightContext.filesDir, "downloads").apply { mkdirs() }
            .let { File(it, "$songId.${track.suffix ?: "mp3"}") }

        return@handler try {
            // Ktor/CIO, not java.net.URL(...).openStream() — the latter goes through
            // the platform's default HttpURLConnection, which (like OkHttp) enforces
            // Android's cleartext-traffic block, undoing the whole point of switching
            // SubsonicClient to CIO. An earlier version of this job used openStream()
            // directly and downloads silently failed against a real http:// server —
            // found via on-device testing + logcat, not caught by compiling.
            //
            // downloadToFile, not destination.writeBytes(api.downloadBytes(songId))
            // — the latter briefly held the whole track as one in-memory
            // ByteArray, which crashed the app outright (OutOfMemoryError) on a
            // real ~30MB track, confirmed on-device 2026-09-18. See
            // SubsonicClient.downloadToFile's doc.
            api.downloadToFile(songId, destination)
            db.downloadDao().upsert(
                DownloadEntity(
                    songId = songId,
                    localFilePath = destination.absolutePath,
                    status = DownloadStatus.COMPLETE,
                    queuedAtEpochMs = System.currentTimeMillis(),
                    completedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            LightJobResult.Success()
        } catch (e: Exception) {
            android.util.Log.e(tag, "download failed for songId=$songId", e)
            AppLogger.e(tag, "download failed for songId=$songId", e)
            destination.delete()
            LightJobResult.Retry // transient (network) failure — let WorkManager back off and retry
        }
    }
