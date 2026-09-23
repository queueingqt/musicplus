package com.musicplus.app.data

import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out one [MusicApi] per saved server, built on first use — [SubsonicApi] or [JellyfinApi] depending on that
 * server's own [ServerProfile.kind]. Every other repository only ever sees the [MusicApi] interface, never which
 * concrete backend it is (formerly this class only ever built [SubsonicApi], hence its own history as
 * `SubsonicApiHolder` — renamed once a second backend existed to build).
 *
 * Most callers want the *active* server ([get]/[peek]) — that is what browsing talks to. Anything that
 * starts from a song, album or playlist id (playing it, downloading it, its art and lyrics, a favorite)
 * wants the server that *owns* that id ([forId]/[peekFor]), which is not always the active one: a queue
 * saved before a server switch still holds the old server's songs and has to keep playing from it.
 */
class ApiHolder(private val serverConfigRepository: ServerConfigRepository) {
    private val apis = ConcurrentHashMap<String, MusicApi>()

    /** Set once by [AppGraph] before any api is built: every client reports whether its server could be reached, and every api what its server can do. */
    var reachability: ServerReachability? = null
    var learner: CapabilityLearner? = null

    private fun apiFor(profile: ServerProfile): MusicApi =
        apis.computeIfAbsent(profile.id) {
            val onReachable: (Boolean) -> Unit = { reachable -> reachability?.report(profile.id, reachable) ?: Unit }
            when (profile.kind) {
                ServerKind.SUBSONIC -> SubsonicApi(profile.id, SubsonicClient(profile.toServerConfig(), onReachable), learner)
                ServerKind.JELLYFIN -> {
                    // Added/edited through ServerConfigRepository, which never saves a Jellyfin profile without first
                    // obtaining these — see its own doc. Null here would mean a saved profile that was never actually
                    // authenticated, which addOrUpdate doesn't allow to happen.
                    val token = requireNotNull(profile.jellyfinAccessToken) { "Jellyfin profile ${profile.id} has no access token" }
                    val userId = requireNotNull(profile.jellyfinUserId) { "Jellyfin profile ${profile.id} has no user id" }
                    JellyfinApi(profile.id, JellyfinClient(JellyfinConfig(profile.baseUrl, token, userId), onReachable))
                }
            }
        }

    /**
     * The active server's api, or null if none is configured. Once built it comes straight from memory: reading
     * the profile means decrypting and decoding the saved server list, and this is called for every cover-art
     * fetch. The warmed active id can trail a server switch by a moment; a call in that gap just reaches the
     * server that was active a moment ago, and everything it touches is scoped to that server.
     */
    suspend fun get(): MusicApi? {
        AppServerPrefs.activeServerId.value.value?.let { id -> apis[id]?.let { return it } }
        return serverConfigRepository.activeProfile.first()?.let { apiFor(it) }
    }

    /** The api for the server that owns [id]. An id that was never scoped falls back to the active server. */
    suspend fun forId(id: String): MusicApi? {
        val serverId = ServerScope.serverOf(id) ?: return get()
        return forServer(serverId)
    }

    suspend fun forServer(serverId: String): MusicApi? {
        apis[serverId]?.let { return it }
        val profile = serverConfigRepository.servers.first().find { it.id == serverId } ?: return null
        return apiFor(profile)
    }

    /** The active server's api if it has been built already — synchronous, so it can be null until the first [get]. */
    fun peek(): MusicApi? = AppServerPrefs.activeServerId.value.value?.let { apis[it] }

    /** Synchronous [forId]: only an api that has been built already. */
    fun peekFor(id: String): MusicApi? = (ServerScope.serverOf(id) ?: AppServerPrefs.activeServerId.value.value)?.let { apis[it] }

    /** Drops one server's api (it was removed). */
    fun forget(serverId: String) {
        apis.remove(serverId)
    }

    /** Drops every built api, so the next use picks up a server's edited address or credentials. */
    fun invalidate() {
        apis.clear()
    }
}
