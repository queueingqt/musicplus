package com.musicplus.app.data

import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out one [SubsonicApi] per saved server, built on first use.
 *
 * Most callers want the *active* server ([get]/[peek]) — that is what browsing talks to. Anything that
 * starts from a song, album or playlist id (playing it, downloading it, its art and lyrics, a favorite)
 * wants the server that *owns* that id ([forId]/[peekFor]), which is not always the active one: a queue
 * saved before a server switch still holds the old server's songs and has to keep playing from it.
 */
class SubsonicApiHolder(private val serverConfigRepository: ServerConfigRepository) {
    private val apis = ConcurrentHashMap<String, SubsonicApi>()

    private fun apiFor(profile: ServerProfile): SubsonicApi =
        apis.computeIfAbsent(profile.id) { SubsonicApi(profile.id, SubsonicClient(profile.toServerConfig())) }

    /**
     * The active server's api, or null if none is configured. Once built it comes straight from memory: reading
     * the profile means decrypting and decoding the saved server list, and this is called for every cover-art
     * fetch. The warmed active id can trail a server switch by a moment; a call in that gap just reaches the
     * server that was active a moment ago, and everything it touches is scoped to that server.
     */
    suspend fun get(): SubsonicApi? {
        AppServerPrefs.activeServerId.value.value?.let { id -> apis[id]?.let { return it } }
        return serverConfigRepository.activeProfile.first()?.let { apiFor(it) }
    }

    /** The api for the server that owns [id]. An id that was never scoped falls back to the active server. */
    suspend fun forId(id: String): SubsonicApi? {
        val serverId = ServerScope.serverOf(id) ?: return get()
        return forServer(serverId)
    }

    suspend fun forServer(serverId: String): SubsonicApi? {
        apis[serverId]?.let { return it }
        val profile = serverConfigRepository.servers.first().find { it.id == serverId } ?: return null
        return apiFor(profile)
    }

    /** The active server's api if it has been built already — synchronous, so it can be null until the first [get]. */
    fun peek(): SubsonicApi? = AppServerPrefs.activeServerId.value.value?.let { apis[it] }

    /** Synchronous [forId]: only an api that has been built already. */
    fun peekFor(id: String): SubsonicApi? = (ServerScope.serverOf(id) ?: AppServerPrefs.activeServerId.value.value)?.let { apis[it] }

    /** Drops one server's api (it was removed). */
    fun forget(serverId: String) {
        apis.remove(serverId)
    }

    /** Drops every built api, so the next use picks up a server's edited address or credentials. */
    fun invalidate() {
        apis.clear()
    }
}
