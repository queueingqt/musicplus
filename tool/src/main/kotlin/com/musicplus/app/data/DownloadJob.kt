package com.musicplus.app.data

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

/**
 * The download job the SDK runs. `@LightJob` has SOURCE retention and marks a top-level `val`: the KSP processor scans for exactly that
 * shape to fill `LightSdkRegistry.jobs`, so this must stay a true top-level declaration (nested in an `object` it is not discovered).
 *
 * All it does is find the song and turn the module's outcome into the SDK's result; the work is [DownloadRepository.run]. The module comes
 * from `AppGraph.from(lightContext)`, the memoized singleton every screen uses: a second Room instance would write rows the app's own
 * Flows never hear about (Room invalidates per instance, not per file), so progress would only show after the screen was reopened. On
 * the rare cold-process run the graph is simply built fresh.
 */
@LightJob(DownloadRepository.JOB_KEY)
val downloadTrack: LightJobHandler = handler@{ lightContext, input ->
    // input["songId"] comes back as the literal string "songId=<value>", not "<value>" (confirmed on-device, 2026-09-17): the SDK's
    // `Data.toStringMap()` maps over the entry, not the value, so `it.toString()` gives Map.Entry's "key=value". Worked around here.
    val songId = input["songId"]?.removePrefix("songId=") ?: run {
        AppLogger.e("MusicPlusDownload", "no songId in input: $input")
        return@handler LightJobResult.Error(reason = "no songId in input")
    }
    when (val outcome = AppGraph.from(lightContext).downloadRepository.run(songId)) {
        DownloadOutcome.Done -> LightJobResult.Success(outputFilePath = null, message = null)
        DownloadOutcome.Retry -> LightJobResult.Retry
        is DownloadOutcome.Failed -> LightJobResult.Error(reason = outcome.reason)
    }
}
