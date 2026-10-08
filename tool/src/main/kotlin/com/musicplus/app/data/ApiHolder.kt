package com.musicplus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/** How code that needs a server's api finds it. [ApiHolder] is the real one; a test supplies its own. */
interface ApiLookup {
    /** The active server's api, or null if none is configured. */
    suspend fun get(): MusicApi?

    /** The api for the server that owns [id]. An id that was never scoped falls back to the active server. */
    suspend fun forId(id: String): MusicApi?

    suspend fun forServer(serverId: String): MusicApi?

    /** The active server's api if it has been built already: synchronous, so it can be null until the first [get]. */
    fun peek(): MusicApi?

    /** Synchronous [forId]: only an api that has been built already. */
    fun peekFor(id: String): MusicApi?
}

/** The saved servers, as far as building their apis goes. */
interface ServerProfiles {
    val servers: Flow<List<ServerProfile>>
    val activeProfile: Flow<ServerProfile?>
}

/**
 * Hands out one [MusicApi] per saved server, built on first use by [adapterFor] ([SubsonicApi] or [JellyfinApi], by that server's own
 * [ServerProfile.kind], unless another is supplied). Every other repository only ever sees the [MusicApi] interface, never which
 * concrete backend it is, and takes this as an [ApiLookup], so a fake can stand in for it (formerly this class only ever built
 * [SubsonicApi], hence its own history as `SubsonicApiHolder`, renamed once a second backend existed to build).
 *
 * Most callers want the *active* server ([get]/[peek]): that is what browsing talks to. Anything that starts from a song, album or
 * playlist id (playing it, downloading it, its art and lyrics, a favorite) wants the server that *owns* that id
 * ([forId]/[peekFor]), which is not always the active one: a queue saved before a server switch still holds the old server's songs and
 * has to keep playing from it.
 */
class ApiHolder(
    private val profiles: ServerProfiles,
    private val adapterFor: (ServerProfile, onReachable: (Boolean) -> Unit, learner: CapabilityLearner?) -> MusicApi = ::defaultAdapter,
    private val activeServerId: () -> String? = { AppServerPrefs.activeServerId.value.value },
) : ApiLookup, PerServerState {
    private val apis = ConcurrentHashMap<String, MusicApi>()

    /** Set once by [AppGraph] before any api is built: every client reports whether its server could be reached, and every api what its server can do. */
    var reachability: ServerReachability? = null
    var learner: CapabilityLearner? = null

    private fun apiFor(profile: ServerProfile): MusicApi =
        apis.computeIfAbsent(profile.id) {
            adapterFor(profile, { reachable -> reachability?.report(profile.id, reachable) ?: Unit }, learner)
        }

    /**
     * The active server's api, or null if none is configured. Once built it comes straight from memory: reading the profile means
     * decrypting and decoding the saved server list, and this is called for every cover-art fetch. The warmed active id can trail a
     * server switch by a moment; a call in that gap just reaches the server that was active a moment ago, and everything it touches
     * is scoped to that server.
     */
    override suspend fun get(): MusicApi? {
        activeServerId()?.let { id -> apis[id]?.let { return it } }
        return profiles.activeProfile.first()?.let { apiFor(it) }
    }

    override suspend fun forId(id: String): MusicApi? {
        val serverId = ServerScope.serverOf(id) ?: return get()
        return forServer(serverId)
    }

    override suspend fun forServer(serverId: String): MusicApi? {
        apis[serverId]?.let { return it }
        val profile = profiles.servers.first().find { it.id == serverId } ?: return null
        return apiFor(profile)
    }

    override fun peek(): MusicApi? = activeServerId()?.let { apis[it] }

    override fun peekFor(id: String): MusicApi? = (ServerScope.serverOf(id) ?: activeServerId())?.let { apis[it] }

    /** Drops one server's api (it was removed). */
    override suspend fun forget(serverId: String) {
        apis.remove(serverId)
    }

    /** Drops a server's api, so the next use picks up its edited address or credentials. */
    override fun edited(serverId: String) {
        apis.remove(serverId)
    }
}

/** The real adapter for a profile: which backend it is decides which one is built. The one place that branches on [ServerKind] to build. */
private fun defaultAdapter(profile: ServerProfile, onReachable: (Boolean) -> Unit, learner: CapabilityLearner?): MusicApi =
    when (profile.kind) {
        ServerKind.SUBSONIC -> SubsonicApi(profile.id, SubsonicClient(profile.toServerConfig(), onReachable), learner)
        ServerKind.JELLYFIN -> {
            // Added/edited through ServerConfigRepository, which never saves a Jellyfin profile without first obtaining these (see its
            // own doc). Null here would mean a saved profile that was never actually authenticated, which addOrUpdate does not allow.
            val token = requireNotNull(profile.jellyfinAccessToken) { "Jellyfin profile ${profile.id} has no access token" }
            val userId = requireNotNull(profile.jellyfinUserId) { "Jellyfin profile ${profile.id} has no user id" }
            JellyfinApi(profile.id, JellyfinClient(JellyfinConfig(profile.baseUrl, token, userId), onReachable))
        }
    }
