package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live mirror of [AppSettingsRepository.showAlbumArtwork], kept in sync by
 * [AppGraph.build] — same pattern as [AppQualityPrefs], for the same reason: [AlbumArt]
 * is composed fresh constantly (every row scrolled into view, every screen
 * navigation), and each one reading the DataStore `Flow` directly via
 * `collectAsState(initial = true)` restarts at that hardcoded `true` default on
 * every single composition, only catching up once its own collection resolves —
 * a visible flash of artwork before it disappears when the setting is off.
 * Confirmed on-device. A `StateFlow` warmed once, at app start, doesn't have
 * this problem: by the time any particular `AlbumArt` actually mounts, [enabled]
 * already reflects the real persisted value.
 */
object AppDisplayPrefs {
    private val _showAlbumArtwork = MutableStateFlow(true)
    val showAlbumArtwork: StateFlow<Boolean> = _showAlbumArtwork.asStateFlow()

    fun setShowAlbumArtwork(value: Boolean) {
        _showAlbumArtwork.value = value
    }
}
