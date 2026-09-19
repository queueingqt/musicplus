package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live mirror of [AppSettingsRepository.debugLoggingEnabled], kept in sync by
 * [AppGraph.build] — same pattern as [AppDisplayPrefs]/[AppQualityPrefs]/
 * [AppServerPrefs], for the same reason: SettingsScreen gets a fresh
 * ViewModel (and a fresh `.stateIn(...)`) every time it's opened, seeded
 * from the raw repository `Flow`'s own default rather than this. Only
 * visibly wrong for someone who's actually turned "Report Crashes" off —
 * the default (true) happens to match the seed either way — but the same
 * gap as every other DataStore-backed toggle in this app, so fixed the
 * same way for consistency.
 */
object AppDebugPrefs {
    private val _debugLoggingEnabled = MutableStateFlow(true)
    val debugLoggingEnabled: StateFlow<Boolean> = _debugLoggingEnabled.asStateFlow()
    fun setDebugLoggingEnabled(value: Boolean) {
        _debugLoggingEnabled.value = value
    }
}
