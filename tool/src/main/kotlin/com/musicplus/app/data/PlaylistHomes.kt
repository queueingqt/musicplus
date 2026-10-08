package com.musicplus.app.data

/**
 * The rules for where a playlist lives. A playlist is on one server, or only on this phone ("Phone Only"), and a server
 * only ever holds its own songs: a song from another server would be an id that server has never heard of.
 */
object PlaylistHomes {
    /** A place a new playlist can be saved. [id] is a server id, or [ServerScope.PHONE]. */
    data class Home(val id: String, val name: String)

    /** True when [serverId] is on and can keep playlists (a server not yet known to lack it counts as able). */
    fun canHold(serverId: String?): Boolean =
        serverId != null &&
            serverId != ServerScope.PHONE &&
            serverId in AppServerPrefs.enabledServerIds.value.value &&
            Capabilities.can(serverId, Capability.PLAYLIST_WRITE)

    /** Where a playlist that starts from [songId] goes: that song's own server, or the phone when that server cannot keep playlists. */
    fun homeOfSong(songId: String): String = ServerScope.serverOf(songId)?.takeIf(::canHold) ?: ServerScope.PHONE

    /** What "Save to" offers for a playlist that starts from nothing: every server that is on and can keep playlists, then Phone Only. */
    fun choices(): List<Home> =
        AppServerPrefs.servers.value.value
            .filter { canHold(it.id) }
            .map { Home(it.id, it.name) } + Home(ServerScope.PHONE, ServerScope.PHONE_ONLY_LABEL)

    /**
     * True when [songId] can be added to [playlistId] as it is: the playlist is Phone Only (it holds anything), or it is on
     * the song's own server and that server can take the change. Anything else would make the playlist differ from the
     * one on its server, so it is added to a Phone Only copy instead, after a warning.
     */
    fun takesAsIs(playlistId: String, songId: String): Boolean {
        val playlistServer = ServerScope.serverOf(playlistId)
        if (playlistServer == ServerScope.PHONE) return true
        return playlistServer != null && playlistServer == ServerScope.serverOf(songId) && canHold(playlistServer)
    }

    /** The warning's second sentence: why this add makes a copy. */
    fun whyCopy(playlistId: String, songId: String): String {
        val playlistServer = ServerScope.serverOf(playlistId)
        return if (playlistServer == ServerScope.serverOf(songId)) {
            "${ServerLabels.nameOf(playlistServer) ?: "This server"} can't save changes to playlists."
        } else {
            "This song is from another server."
        }
    }
}
