package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.LightConnectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** What a server may or may not be able to do. Not every Subsonic-compatible server (Bandcamp's, for one) offers all of it. */
enum class Capability(
    /** The harmless request that shows whether a server offers it, and its parameters. Ids here name nothing that exists. */
    val probeMethod: String,
    val probeParams: List<Pair<String, String>>,
) {
    /** Favorites. The starred list is what the app reads them back from, so it is the thing to ask. */
    STAR("getStarred2.view", emptyList()),
    /** "Now playing" for an id that does not exist: a server that has scrobbling refuses it as "not found". */
    SCROBBLE("scrobble.view", listOf("id" to PROBE_ID, "submission" to "false")),
    LYRICS("getLyricsBySongId.view", listOf("id" to PROBE_ID)),
    /** Changing a playlist that does not exist: a server that can write playlists refuses it as "not found". */
    PLAYLIST_WRITE("updatePlaylist.view", listOf("playlistId" to PROBE_ID)),
}

private const val PROBE_ID = "musicplus-probe-nothing"

/**
 * What is known about one server. A null answer has not been found out yet, and counts as "can": the first real request
 * finds out. [probedBy] is the app build that last ran the probes; a different build runs them again.
 */
@Serializable
data class ServerCapabilities(
    val star: Boolean? = null,
    val scrobble: Boolean? = null,
    val lyrics: Boolean? = null,
    val playlistWrite: Boolean? = null,
    val probedBy: String? = null,
) {
    operator fun get(capability: Capability): Boolean? = when (capability) {
        Capability.STAR -> star
        Capability.SCROBBLE -> scrobble
        Capability.LYRICS -> lyrics
        Capability.PLAYLIST_WRITE -> playlistWrite
    }

    fun with(capability: Capability, value: Boolean?): ServerCapabilities = when (capability) {
        Capability.STAR -> copy(star = value)
        Capability.SCROBBLE -> copy(scrobble = value)
        Capability.LYRICS -> copy(lyrics = value)
        Capability.PLAYLIST_WRITE -> copy(playlistWrite = value)
    }
}

/** Reads what is known about a server from the warmed copy, so it can be asked from anywhere, on any thread. */
object Capabilities {
    /** True unless the server is known not to offer it. */
    fun can(serverId: String?, capability: Capability): Boolean =
        serverId == null || AppServerPrefs.capabilities.value.value[serverId]?.get(capability) != false

    /** True only when the server is known not to offer it. */
    fun cannot(serverId: String?, capability: Capability): Boolean = !can(serverId, capability)
}

/** How a real request reports back to the [CapabilityRegistry]. */
interface CapabilityLearner {
    /** A real request for [capability] worked. */
    fun worked(serverId: String, capability: Capability)

    /** A real request for [capability] failed in a way that looks like "this server does not do that". */
    fun doubted(serverId: String, capability: Capability)
}

/**
 * Finds out, remembers and corrects what each server can do.
 *
 * It asks with harmless requests (see [Capability]) when a server is added or turned on, and again after every app
 * update, since a server such as Bandcamp's may add features later. What it finds is kept per server. It is also
 * corrected by real use: a real request that works marks the feature as offered, and one that fails as if the server
 * had no such feature makes it ask again.
 *
 * Every question is put only to a server that has just answered an ordinary one ([SubsonicClient.checkLogin]),
 * so a server that is merely down or slow is never written off. What could not be told is left as it was.
 */
class CapabilityRegistry(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val apiHolder: ApiHolder,
    private val connectivity: LightConnectivity,
    /** Identifies this build of the app; a different one probes again. */
    private val build: String,
) : CapabilityLearner {
    private val key = stringPreferencesKey("server_capabilities_json")
    private val serializer = MapSerializer(String.serializer(), ServerCapabilities.serializer())

    val all: Flow<Map<String, ServerCapabilities>> = dataStore.data.map { decode(it[key]) }

    /** Called with a server's id when it did not offer favorites before and now does; AppGraph sends the hearts kept on the phone to it. */
    var onStarGained: (String) -> Unit = {}

    private val probing = ConcurrentHashMap.newKeySet<String>()
    private val lastDoubtProbeAt = ConcurrentHashMap<String, Long>()

    /** Probes each server the first time it is seen, again after an app update, and whenever it is turned on. Needs a connection. */
    fun watch(enabledServers: Flow<List<ServerProfile>>) {
        scope.launch {
            var known: Set<String>? = null
            combine(enabledServers, connectivity.observeNetworkStatus().map { it.isConnected }.distinctUntilChanged()) { servers, online -> servers to online }
                .collect { (servers, online) ->
                    val ids = servers.mapTo(HashSet()) { it.id }
                    val previous = known
                    known = ids
                    if (!online) return@collect
                    val stored = all.first()
                    for (id in ids) {
                        val turnedOn = previous != null && id !in previous
                        if (turnedOn || stored[id]?.probedBy != build) probeSoon(id)
                    }
                }
        }
    }

    fun probeSoon(serverId: String) {
        if (!probing.add(serverId)) return
        scope.launch {
            try {
                probe(serverId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Capabilities", "probe of server $serverId failed", e)
            } finally {
                probing.remove(serverId)
            }
        }
    }

    private suspend fun probe(serverId: String) {
        val profile = AppServerPrefs.servers.value.value.find { it.id == serverId } ?: return
        if (profile.kind == ServerKind.JELLYFIN) {
            // A real Jellyfin server's whole feature set is documented by its own API — nothing here is undocumented
            // the way an arbitrary Subsonic mount (Bandcamp) can be, so there is nothing to find out by probing.
            // scrobble is deliberately left false: it means "this server takes a Subsonic-style scrobble.view relay",
            // which Jellyfin has no equivalent of — its own playback-progress reporting is unconditional and never
            // goes through this capability at all. See MusicApi's class doc and PlaybackRepository's Jellyfin watcher.
            save(serverId) { ServerCapabilities(star = true, scrobble = false, lyrics = true, playlistWrite = true, probedBy = build) }
            AppLogger.d("Capabilities", "${describe(serverId)}: Jellyfin, star/lyrics/playlistWrite assumed offered")
            return
        }
        // probe() itself is Subsonic-specific (its harmless-request vocabulary is .view endpoints) — only ever
        // reached for a Subsonic profile, per the branch just above.
        val api = apiHolder.forServer(serverId) as? SubsonicApi ?: return
        if (api.checkLogin().isFailure) {
            AppLogger.d("Capabilities", "${describe(serverId)} did not answer an ordinary request, will ask again later")
            return
        }
        val found = Capability.entries.associateWith { api.probe(it) }
        save(serverId) { current ->
            var next = current.copy(probedBy = build)
            for ((capability, support) in found) {
                when (support) {
                    Support.YES -> next = next.with(capability, true)
                    Support.NO -> next = next.with(capability, false)
                    Support.UNKNOWN -> {}
                }
            }
            next
        }
        AppLogger.d("Capabilities", "${describe(serverId)}: " + found.entries.joinToString(", ") { "${it.key.name.lowercase()}=${it.value.name.lowercase()}" })
    }

    override fun worked(serverId: String, capability: Capability) {
        if (AppServerPrefs.capabilities.value.value[serverId]?.get(capability) == true) return
        scope.launch { save(serverId) { it.with(capability, true) } }
        AppLogger.d("Capabilities", "${describe(serverId)}: ${capability.name.lowercase()} worked, marking it as offered")
    }

    override fun doubted(serverId: String, capability: Capability) {
        val now = System.currentTimeMillis()
        val last = lastDoubtProbeAt[serverId]
        // One failing real request must not turn into a probe per request.
        if (last != null && now - last < DOUBT_PROBE_GAP_MS) return
        lastDoubtProbeAt[serverId] = now
        AppLogger.d("Capabilities", "${describe(serverId)}: ${capability.name.lowercase()} failed like a missing feature, checking again")
        probeSoon(serverId)
    }

    /** Forgets a removed server. */
    suspend fun forget(serverId: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (serverId in current) prefs[key] = Json.encodeToString(serializer, current - serverId)
        }
    }

    private suspend fun save(serverId: String, change: (ServerCapabilities) -> ServerCapabilities) {
        var starGained = false
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            val before = current[serverId] ?: ServerCapabilities()
            val after = change(before)
            if (before.star == false && after.star == true) starGained = true
            if (after != before || serverId !in current) prefs[key] = Json.encodeToString(serializer, current + (serverId to after))
        }
        if (starGained) onStarGained(serverId)
    }

    private fun describe(serverId: String) =
        "server \"${AppServerPrefs.servers.value.value.find { it.id == serverId }?.name ?: serverId}\""

    private fun decode(stored: String?): Map<String, ServerCapabilities> =
        stored?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        const val DOUBT_PROBE_GAP_MS = 10 * 60 * 1000L
    }
}
