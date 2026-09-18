package com.musicplus.app.data

import kotlinx.coroutines.flow.first

/**
 * Lazily resolves a [SubsonicApi] from whatever server config is currently saved.
 * Exists because `LightScreen.createViewModel()` is a plain synchronous function
 * (see SDK reference notes) — repositories have to be constructible without
 * awaiting an async DataStore read, so the actual resolution happens on first use
 * from a suspend context instead of at construction time.
 */
class SubsonicApiHolder(private val serverConfigRepository: ServerConfigRepository) {
    @Volatile private var cached: SubsonicApi? = null

    /** Resolves (and caches) the API client. Returns null if no server is configured yet. */
    suspend fun get(): SubsonicApi? {
        cached?.let { return it }
        val config = serverConfigRepository.serverConfig.first() ?: return null
        return SubsonicApi(SubsonicClient(config)).also { cached = it }
    }

    /** Best-effort synchronous read for non-suspend call sites (e.g. Flow.map). Null until [get] has run once. */
    fun peek(): SubsonicApi? = cached

    /** Call after Settings saves a new server config so the next [get] re-resolves instead of reusing a stale client. */
    fun invalidate() {
        cached = null
    }
}
