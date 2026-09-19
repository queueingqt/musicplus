package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live mirror of [ServerConfigRepository]'s servers/activeServerId/serverConfig,
 * kept in sync by [AppGraph.build] — same pattern as [AppQualityPrefs]/
 * [AppDisplayPrefs], for the same reason: HomeScreen/ServerSettingsScreen each
 * get a fresh ViewModel (and a fresh `.stateIn(...)`) every time they're
 * navigated to, and that `.stateIn` has to start from *some* seed before the
 * DataStore `Flow` behind [ServerConfigRepository] actually emits. Reported
 * live, 2026-09-18: Settings' "Server" row briefly flashed "No servers yet"
 * on open even with servers already saved — the exact same root cause
 * already fixed once for [AppDisplayPrefs]/[AppQualityPrefs] ("glitching" on
 * open), just not yet applied here. HomeScreen's own `isConfigured` (drives
 * whether the search icon shows at all) had the identical gap — a fresh
 * `false` seed on every re-visit, briefly hiding it even once a server was
 * genuinely configured.
 */
object AppServerPrefs {
    private val _servers = MutableStateFlow<List<ServerProfile>>(emptyList())
    val servers: StateFlow<List<ServerProfile>> = _servers.asStateFlow()
    fun setServers(value: List<ServerProfile>) {
        _servers.value = value
    }

    private val _activeServerId = MutableStateFlow<String?>(null)
    val activeServerId: StateFlow<String?> = _activeServerId.asStateFlow()
    fun setActiveServerId(value: String?) {
        _activeServerId.value = value
    }

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()
    fun setIsConfigured(value: Boolean) {
        _isConfigured.value = value
    }
}
