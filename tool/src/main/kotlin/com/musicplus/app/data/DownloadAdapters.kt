package com.musicplus.app.data

import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.first

/** [DownloadScheduler] on the phone: the SDK's work queue, keyed by the song id. */
class LightWorkDownloadScheduler(private val lightContext: SealedLightContext) : DownloadScheduler {
    override suspend fun schedule(songId: String) {
        LightWork.enqueue(
            lightContext = lightContext,
            jobKey = DownloadRepository.JOB_KEY,
            inputData = mapOf("songId" to songId),
            tag = songId,
        )
    }

    override suspend fun cancel(songId: String) {
        LightWork.cancel(lightContext, jobKeyOrTag = songId)
    }
}

/** [DownloadSources] on the phone: the song's row, the owning server's api, and the "Downloads" quality setting. */
class AppDownloadSources(
    private val trackDao: TrackDao,
    private val apiHolder: ApiLookup,
    private val appSettingsRepository: AppSettingsRepository,
) : DownloadSources {
    override suspend fun track(songId: String): DownloadTrack? = trackDao.getById(songId)?.let { DownloadTrack(it.suffix) }
    override suspend fun api(songId: String): MusicApi? = apiHolder.forId(songId)
    override suspend fun maxBitRateKbps(): Int? = appSettingsRepository.downloadQuality.first()
}

/**
 * [DownloadGate] through [FetchGate]: the playing and up-next songs first (their rank comes from the player when one exists), then
 * downloads, then retries.
 */
class FetchGateDownloadGate(private val gate: FetchGate) : DownloadGate {
    override suspend fun run(songId: String, retried: Boolean, transcoding: Boolean, block: suspend (FetchGate.Lease?) -> Unit): Boolean {
        val rank: () -> FetchGate.Priority = {
            PlaybackRepositoryHolder.peek()?.priorityForSong(songId)
                ?: if (retried) FetchGate.Priority.RETRY else FetchGate.Priority.DOWNLOAD
        }
        return gate.run(FetchGate.Lane.BULK, songId, rank, transcoding) { lease -> block(lease) } != null
    }
}
