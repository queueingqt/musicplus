package com.musicplus.app.data

/** See [WarmedFlow]'s doc. Mirrors [AppSettingsRepository.showAlbumArtwork]. */
object AppDisplayPrefs {
    val showAlbumArtwork = WarmedFlow(true)

    /** Mirrors [AppSettingsRepository.downloadedOnly]. */
    val downloadedOnly = WarmedFlow(false)
}
