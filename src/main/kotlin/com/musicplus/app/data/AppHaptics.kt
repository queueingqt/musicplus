package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live mirror of [AppSettingsRepository.hapticFeedbackEnabled], kept in sync by
 * [AppGraph.build]. A plain process-lifetime singleton, not something threaded
 * through every `lightClickable` call site — [com.musicplus.app.MusicPlusScaffold]
 * reads this once and provides it into `LocalHapticsEnabled`, so every
 * `lightClickable` everywhere in the app picks it up automatically via the SDK's
 * own composition-local mechanism.
 */
object AppHaptics {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }
}
