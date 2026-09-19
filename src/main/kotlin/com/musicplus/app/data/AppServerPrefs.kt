package com.musicplus.app.data

/**
 * See [WarmedFlow]'s doc. Mirrors [ServerConfigRepository]'s
 * servers/activeServerId/serverConfig.
 */
object AppServerPrefs {
    val servers = WarmedFlow<List<ServerProfile>>(emptyList())
    val activeServerId = WarmedFlow<String?>(null)
    val isConfigured = WarmedFlow(false)
}
