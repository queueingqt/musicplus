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

        // Reported live: a download that hit a real, non-transient error (e.g. the
        // server genuinely unreachable) looked identical to one still in progress
        // forever — downloadTrack's catch block always returned Retry, and nothing
        // ever wrote DownloadStatus.FAILED, so the UI's already-built "Failed — tap
        // to retry" state was simply never reached. WorkManager's own Result.retry()
        // has no built-in attempt ceiling (it backs off, capped at its own max
        // delay, but keeps trying indefinitely) — giving up is something the job
        // itself has to decide, tracked here since WorkManager passes no attempt
        // count into the handler itself. 3 is enough to ride out a brief blip
        // across a few backoff cycles without leaving something well and truly
        // stuck look identical to "still trying" for hours.
        const val MAX_DOWNLOAD_ATTEMPTS = 3
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
                attemptCount = 0,
            ),
        )
        LightWork.enqueue(
            lightContext = lightContext,
            jobKey = JOB_KEY,
            inputData = mapOf("songId" to track.id),
            tag = track.id,
        )
    }

    /**
     * Reads the real on-disk path off the entity's own [DownloadEntity.localFilePath]
     * — not reconstructed by guessing an extension. Reported live: this used to call
     * a [localFile] helper that always assumed `.mp3`, but [downloadTrack] only ever
     * writes `.mp3` when a quality cap forces server-side transcoding; a track
     * downloaded at "Original" quality (the default) is saved under its real source
     * suffix (flac/ogg/m4a/wav/...). Cancelling one of those silently deleted the
     * wrong (nonexistent) file, leaving the real audio orphaned on disk forever while
     * the DB row — and therefore the UI — reported it as removed. The entity already
     * has the one true path (written by [downloadTrack] on COMPLETE); this just reads
     * it back instead of re-deriving a guess.
     */
    suspend fun cancel(lightContext: SealedLightContext, songId: String) {
        LightWork.cancel(lightContext, jobKeyOrTag = songId)
        downloadDao.getBySongId(songId)?.localFilePath?.let { File(it).delete() }
        downloadDao.delete(songId)
    }

    /**
     * Re-arms every download that gave up after [MAX_DOWNLOAD_ATTEMPTS] — called
     * once per process start (see AppGraph.build()), same "give it a fresh shot
     * every time the app reopens" behavior the sync queue already has via its
     * own reconnect-triggered enqueue. Resets attemptCount back to 0 rather than
     * continuing to count against the exhausted total, so a download that failed
     * because of a since-resolved problem (server was down, Tailscale was
     * disconnected, etc.) gets the same 3 fresh attempts as a brand new one,
     * not zero.
     */
    suspend fun retryFailed(lightContext: SealedLightContext) {
        for (entity in downloadDao.getByStatus(DownloadStatus.FAILED)) {
            downloadDao.upsert(entity.copy(status = DownloadStatus.QUEUED, attemptCount = 0))
            LightWork.enqueue(
                lightContext = lightContext,
                jobKey = JOB_KEY,
                inputData = mapOf("songId" to entity.songId),
                tag = entity.songId,
            )
        }
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
        // Preferences > Streaming quality's "Downloads" setting — null (the
        // default) means the original file, same as this job's behavior
        // before that setting existed. A non-null cap means the server
        // transcodes on the fly, which per AppSettingsRepository.downloadQuality's
        // doc Navidrome (and stream.view generally) returns as mp3 regardless
        // of the source format — same convention PlaybackRepository.cachedStreamFile
        // already uses for the same reason, so the extension has to be
        // decided the same way here, not trusted from track.suffix (the
        // *original* format, wrong once transcoding is actually happening).
        val maxBitRateKbps = AppGraph.from(lightContext).appSettingsRepository.downloadQuality.first()
        val destination = File(lightContext.filesDir, "downloads").apply { mkdirs() }
            .let { File(it, "$songId.${if (maxBitRateKbps != null) "mp3" else (track.suffix ?: "mp3")}") }

        // Read before the attempt, not in the catch block — a WorkManager retry is a
        // fresh invocation of this same handler, so attemptCount has to be persisted
        // between calls rather than tracked in a local var (nothing here survives
        // across retries except what's written to the DB).
        val previousAttempts = db.downloadDao().getBySongId(songId)?.attemptCount ?: 0

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
            //
            // streamToFile (not downloadToFile) when a quality cap is set —
            // download.view has no maxBitRate parameter at all (confirmed via
            // SubsonicApi.downloadToFile's own doc: "original file" only), so
            // getting a transcoded download means going through stream.view,
            // same endpoint live playback already uses for this.
            if (maxBitRateKbps != null) {
                api.streamToFile(songId, destination, maxBitRateKbps)
            } else {
                api.downloadToFile(songId, destination)
            }
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

            val attempt = previousAttempts + 1
            if (attempt >= DownloadRepository.MAX_DOWNLOAD_ATTEMPTS) {
                db.downloadDao().upsert(
                    DownloadEntity(
                        songId = songId,
                        localFilePath = null,
                        status = DownloadStatus.FAILED,
                        queuedAtEpochMs = System.currentTimeMillis(),
                        completedAtEpochMs = null,
                        attemptCount = attempt,
                    ),
                )
                LightJobResult.Error() // exhausted — stop WorkManager's own retries too, let the user retry from the UI
            } else {
                db.downloadDao().upsert(
                    DownloadEntity(
                        songId = songId,
                        localFilePath = null,
                        status = DownloadStatus.QUEUED,
                        queuedAtEpochMs = System.currentTimeMillis(),
                        completedAtEpochMs = null,
                        attemptCount = attempt,
                    ),
                )
                LightJobResult.Retry // transient (network) failure — let WorkManager back off and retry
            }
        }
    }
