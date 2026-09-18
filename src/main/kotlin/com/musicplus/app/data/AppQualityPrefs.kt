package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live mirror of [AppSettingsRepository]'s streamQualityWifi/streamQualityCellular/
 * downloadQuality, kept in sync by [AppGraph.build] — same pattern as
 * [AppDisplayPrefs]/[AppHaptics], for the same reason: QualitySettingsScreen gets a
 * fresh ViewModel (and a fresh `.stateIn(...)`) every time it's navigated to, and
 * that `.stateIn` has to start from *some* seed before the DataStore `Flow` behind
 * it actually emits — reading the raw repository `Flow` directly seeded every
 * fresh open at a wrong default, then visibly snapped to the real value a frame
 * or two later. Reported live as the settings "glitching" on open. A `StateFlow`
 * warmed once, at app start, doesn't have this problem: by the time the screen
 * mounts, these already reflect the real persisted values.
 *
 * Defaults here match [AppSettingsRepository]'s own — only relevant for the
 * narrow window before the real collector (in [AppGraph.build]) delivers its
 * first value, which in practice is effectively immediate.
 */
object AppQualityPrefs {
    private val _streamQualityWifi = MutableStateFlow<Int?>(320)
    val streamQualityWifi: StateFlow<Int?> = _streamQualityWifi.asStateFlow()
    fun setStreamQualityWifi(value: Int?) {
        _streamQualityWifi.value = value
    }

    private val _streamQualityCellular = MutableStateFlow<Int?>(192)
    val streamQualityCellular: StateFlow<Int?> = _streamQualityCellular.asStateFlow()
    fun setStreamQualityCellular(value: Int?) {
        _streamQualityCellular.value = value
    }

    private val _downloadQuality = MutableStateFlow<Int?>(null)
    val downloadQuality: StateFlow<Int?> = _downloadQuality.asStateFlow()
    fun setDownloadQuality(value: Int?) {
        _downloadQuality.value = value
    }
}
