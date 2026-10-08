package com.musicplus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Where each server stands right now (on or off, reachable, what it offers, what it is called), and the questions the rest of the app asks of
 * that: is it usable, does it keep favorites, can it hold playlists, can it count a listen, what is it called. Each of those used to be decided
 * where it was needed, from the raw state ("a server without favorites keeps hearts on the phone" was four separate tests); now they are decided
 * here once, and the places that need an answer ask.
 *
 * A plain value over the state it is given, so a test builds one directly. [ServerStandingNow] is the app's: the same rules over the warmed copies.
 */
class ServerStanding(
    val servers: List<ServerProfile> = emptyList(),
    val enabled: Set<String> = emptySet(),
    val unreachable: Set<String> = emptySet(),
    val capabilities: Map<String, ServerCapabilities> = emptyMap(),
    val removed: List<RemovedServer> = emptyList(),
) {
    /** True when [serverId] is on and was reachable the last time it was asked; true for no server (nothing to wait for) and while nothing is known about any server yet (the saved list has not been read), rather than greying everything out. */
    fun usable(serverId: String?): Boolean = usable(serverId, servers.isNotEmpty(), enabled, unreachable)

    /** True unless the server is known not to offer [capability]; what is not yet known counts as "can", the first real request finds out. */
    fun can(serverId: String?, capability: Capability): Boolean =
        serverId == null || capabilities[serverId]?.get(capability) != false

    fun cannot(serverId: String?, capability: Capability): Boolean = !can(serverId, capability)

    /** False for a server known not to keep favorites: a heart on its songs stays on the phone and is never sent. */
    fun keepsFavorites(serverId: String?): Boolean = can(serverId, Capability.STAR)

    /** True when [serverId] is on and can keep playlists. The phone is not a server: its playlists are not held by one. */
    fun canHoldPlaylists(serverId: String?): Boolean =
        serverId != null && serverId != ServerScope.PHONE && serverId in enabled && can(serverId, Capability.PLAYLIST_WRITE)

    /** True when [serverId] is on and takes a scrobble. */
    fun canScrobble(serverId: String?): Boolean = serverId != null && serverId in enabled && can(serverId, Capability.SCROBBLE)

    /** A server's name for a row: "Phone Only" for the phone, the saved name, or "<name> (removed)" for a server removed with its downloads kept. */
    fun nameOf(serverId: String?): String? {
        if (serverId == null) return null
        if (serverId == ServerScope.PHONE) return ServerScope.PHONE_ONLY_LABEL
        servers.find { it.id == serverId }?.let { return it.name }
        return removed.find { it.id == serverId }?.let { "${it.name} (removed)" }
    }

    companion object {
        /** The usable rule over state a caller has collected itself (a composable that must grey a row the moment a server drops). */
        fun usable(serverId: String?, anyServerKnown: Boolean, enabled: Set<String>, unreachable: Set<String>): Boolean =
            serverId == null || !anyServerKnown || (serverId in enabled && serverId !in unreachable)
    }
}

/** [ServerStanding] over the app's warmed copies, so it can be asked from anywhere, on any thread. */
object ServerStandingNow {
    fun current(): ServerStanding = ServerStanding(
        servers = AppServerPrefs.servers.value.value,
        enabled = AppServerPrefs.enabledServerIds.value.value,
        unreachable = AppServerPrefs.unreachableServerIds.value.value,
        capabilities = AppServerPrefs.capabilities.value.value,
        removed = AppServerPrefs.removedServers.value.value,
    )

    /** The standing again whenever any of what it is made of changes. */
    val changes: Flow<ServerStanding> = combine(
        AppServerPrefs.servers.value,
        AppServerPrefs.enabledServerIds.value,
        AppServerPrefs.unreachableServerIds.value,
        AppServerPrefs.capabilities.value,
        AppServerPrefs.removedServers.value,
    ) { servers, enabled, unreachable, capabilities, removed -> ServerStanding(servers, enabled, unreachable, capabilities, removed) }
}
