package com.musicplus.app.data

/**
 * See [WarmedFlow]'s doc. Mirrors [AppSettingsRepository]'s
 * streamQualityWifi/streamQualityCellular/downloadQuality.
 */
object AppQualityPrefs {
    val streamQualityWifi = WarmedFlow<Int?>(320)
    val streamQualityCellular = WarmedFlow<Int?>(192)
    val downloadQuality = WarmedFlow<Int?>(null)
}
