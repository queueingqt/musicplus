package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Artist
import com.musicplus.app.Playlist
import com.musicplus.app.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** How the servers stand at one moment: what [TrackAvailability.serverUsable] needs, kept together so a list is judged against one consistent picture. */
data class ServersNow(val anyKnown: Boolean, val enabled: Set<String>, val unreachable: Set<String>) {
    fun usable(serverId: String?): Boolean = TrackAvailability.serverUsable(serverId, anyKnown, enabled, unreachable)
}

/**
 * A list cut in two: the rows that can be played now, then the rows that cannot. Each keeps the order it had.
 * [loaded] is false until the library behind the list has answered once: an empty list that is not loaded yet is not "empty".
 */
data class AvailableSplit<T>(val playable: List<T>, val unavailable: List<T>, val loaded: Boolean = true) {
    val isEmpty: Boolean get() = playable.isEmpty() && unavailable.isEmpty()

    /** The line between the two groups is only there to tell them apart: with nothing playable above it there is nothing to tell them from. */
    val hasLine: Boolean get() = playable.isNotEmpty() && unavailable.isNotEmpty()

    companion object {
        fun <T> empty() = AvailableSplit<T>(emptyList(), emptyList())

        /** What a list holds before its library has answered. */
        fun <T> notLoaded() = AvailableSplit<T>(emptyList(), emptyList(), loaded = false)
    }
}

/**
 * Which rows of a list can be played right now, and so where they go: the playable ones first, then the ones that cannot be.
 *
 * A row is *playable* when its server is on and reachable, or the phone holds audio for it ([OnPhoneIndex]; for an album, artist or
 * playlist that means at least one of its songs). While every server is fine nothing is unavailable, so a list keeps its order
 * exactly. With [downloadedOnly] a list shows only what the phone holds audio for, whatever the servers are doing, and nothing is
 * "unavailable" because nothing that would be is shown.
 */
class ListAvailability(
    private val on: OnPhoneIndex,
    private val servers: ServersNow,
    private val downloadedOnly: Boolean,
) {
    fun songs(rows: List<Track>): AvailableSplit<Track> =
        split(rows, { servers.usable(ServerScope.serverOf(it.id)) }, { it.id in on.songs })

    fun albums(rows: List<Album>): AvailableSplit<Album> =
        split(rows, { servers.usable(ServerScope.serverOf(it.id)) }, { it.id in on.albums })

    fun artists(rows: List<Artist>): AvailableSplit<Artist> =
        split(rows, { servers.usable(ServerScope.serverOf(it.id)) }, { it.id in on.artists })

    fun playlists(rows: List<Playlist>): AvailableSplit<Playlist> =
        split(rows, ::playlistUsable, { it.id in on.playlists })

    /**
     * A playlist on a server follows that server. A Phone Only one has no server, so it follows its songs: it can be played when any of
     * them can be reached (or has audio, which [split] checks); one with no songs has nothing that could be unavailable.
     */
    private fun playlistUsable(playlist: Playlist): Boolean {
        val owner = ServerScope.serverOf(playlist.id)
        if (owner != null && owner != ServerScope.PHONE) return servers.usable(owner)
        val memberServers = on.playlistServers[playlist.id] ?: return true
        return memberServers.any { servers.usable(it) }
    }

    private fun <T> split(rows: List<T>, usable: (T) -> Boolean, hasAudio: (T) -> Boolean): AvailableSplit<T> {
        if (downloadedOnly) return AvailableSplit(rows.filter(hasAudio), emptyList())
        val (playable, unavailable) = rows.partition { usable(it) || hasAudio(it) }
        return AvailableSplit(playable, unavailable)
    }
}

/**
 * The one [ListAvailability] every list reads, kept current for the whole process (see [WarmedFlow] for why a list must not start from
 * a default and then snap): rebuilt whenever a server goes down or comes back, a song's audio arrives or leaves the phone, or
 * "Downloaded only" changes. Mirrored once in [AppGraph.build].
 */
object AppAvailability {
    val index = WarmedFlow(OnPhoneIndex.EMPTY)

    /** Until the servers are known everything counts as usable, so nothing is put below the line on a guess. */
    val now = WarmedFlow(ListAvailability(OnPhoneIndex.EMPTY, ServersNow(anyKnown = false, enabled = emptySet(), unreachable = emptySet()), downloadedOnly = false))

    // Every list's rows, already cut by [now] (see [ListAvailability]) and kept current off the main thread, for the same reason [now] is warmed: a
    // list that opens from a default and then fills in shows its "No albums yet" note for the frames in between (measured on the phone: 100-280 ms).
    val songs = WarmedFlow(AvailableSplit.notLoaded<Track>())
    val albums = WarmedFlow(AvailableSplit.notLoaded<Album>())
    val artists = WarmedFlow(AvailableSplit.notLoaded<Artist>())
    val playlists = WarmedFlow(AvailableSplit.notLoaded<Playlist>())
    val favoriteTracks = WarmedFlow(AvailableSplit.notLoaded<Track>())
    val favoriteAlbums = WarmedFlow(AvailableSplit.notLoaded<Album>())
    val favoriteArtists = WarmedFlow(AvailableSplit.notLoaded<Artist>())

    /**
     * [rows] cut by [now]: what [AppGraph.build] mirrors into each of the lists above. [by] is one of [ListAvailability]'s functions and
     * [rowsLoaded] is whether the library behind [rows] has answered yet. The result is [AvailableSplit.loaded] only once that, and everything the
     * "No albums yet" line reads (the saved servers, which are on, when each last synced), has too.
     */
    fun <T> split(rows: Flow<List<T>>, rowsLoaded: Flow<Boolean>, by: ListAvailability.(List<T>) -> AvailableSplit<T>): Flow<AvailableSplit<T>> {
        val ready = combine(
            listOf(rowsLoaded, AppServerPrefs.servers.loaded, AppServerPrefs.enabledServerIds.loaded, AppServerPrefs.lastSyncedAt.loaded, AppServerPrefs.isConfigured.loaded),
        ) { flags -> flags.all { it } }
        return combine(rows, ready, now.value) { list, isReady, availability -> availability.by(list).copy(loaded = isReady) }
    }

    /** [now]'s inputs, joined: what [AppGraph.build] mirrors into it. */
    fun observe() = combine(
        index.value,
        AppServerPrefs.servers.value,
        AppServerPrefs.enabledServerIds.value,
        AppServerPrefs.unreachableServerIds.value,
        AppDisplayPrefs.downloadedOnly.value,
    ) { on, servers, enabled, unreachable, downloadedOnly ->
        ListAvailability(on, ServersNow(servers.isNotEmpty(), enabled, unreachable), downloadedOnly)
    }
}
