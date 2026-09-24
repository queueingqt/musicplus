package com.musicplus.app.data.playback

import com.musicplus.app.data.ApiHolder
import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.AppScrobblePrefs
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.Capabilities
import com.musicplus.app.data.Capability
import com.musicplus.app.data.JellyfinApi
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.ServerKind
import com.musicplus.app.data.ServerScope
import com.musicplus.app.data.SubsonicApi

/**
 * Counts a listen on a server that offers scrobbling. Off by default (see `AppSettingsRepository.scrobblingEnabled`): "now
 * playing" (submission=false) when a listen starts, the real scrobble (submission=true) once it counts. Failures are logged and
 * surfaced to [AppScrobblePrefs.lastError] (cleared on the next success), never swallowed.
 */
class ScrobbleSink(
    private val apiHolder: ApiHolder,
    private val libraryRepository: LibraryRepository,
    private val enabled: suspend () -> Boolean,
) : ListenSink {
    override suspend fun started(listen: Listen, positionMs: Long) = send(listen.trackId, submission = false)

    override suspend fun counted(listen: Listen) = send(listen.trackId, submission = true)

    private suspend fun send(songId: String, submission: Boolean) {
        if (!enabled()) return
        val target = scrobbleTarget(songId)
        if (target == null) {
            AppLogger.d(TAG, "scrobble(songId=$songId): no server can count it")
            return
        }
        // scrobble() is Subsonic-only (not part of MusicApi); scrobbleTarget() only returns a target whose server has
        // Capability.SCROBBLE, which a Jellyfin server is never given, so this cast always succeeds in practice.
        val api = apiHolder.forId(target) as? SubsonicApi ?: return
        try {
            api.scrobble(target, submission)
            AppLogger.d(TAG, "scrobble(songId=$songId, submission=$submission) succeeded" + if (target != songId) " on another server, as $target" else "")
            AppScrobblePrefs.lastError.set(null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "scrobble(songId=$songId, submission=$submission) failed", e)
            AppScrobblePrefs.lastError.set(e.message ?: "Scrobble failed")
        }
    }

    private fun canScrobbleOn(serverId: String?): Boolean =
        serverId != null && serverId in AppServerPrefs.enabledServerIds.value.value && Capabilities.can(serverId, Capability.SCROBBLE)

    /**
     * Which song to scrobble, so the play is counted somewhere: the song itself when its own server is on and offers
     * scrobbling, otherwise the exact same song (same artist, title and album) on another server that does, otherwise
     * none (the play is not counted).
     */
    private suspend fun scrobbleTarget(songId: String): String? {
        if (canScrobbleOn(ServerScope.serverOf(songId))) return songId
        return libraryRepository.sameSongElsewhere(songId).firstOrNull { canScrobbleOn(ServerScope.serverOf(it)) }
    }

    private companion object {
        const val TAG = "ScrobbleSink"
    }
}

/**
 * Reports playback to a Jellyfin server: unconditional, never gated by the scrobbling setting, because it drives that server's
 * own resume position and play history and has nothing to do with a Last.fm relay. Start when a Jellyfin track's listen begins,
 * Progress at the [ListenReporting] interval for as long as it is current (playing or paused, so a pause still saves roughly
 * where playback left off), Stopped once it is not.
 */
class JellyfinPlayReportSink(private val apiHolder: ApiHolder) : ListenSink {
    override fun wantsProgress(listen: Listen): Boolean = isJellyfinTrack(listen.trackId)

    override suspend fun started(listen: Listen, positionMs: Long) {
        api(listen)?.reportPlaybackStart(listen.trackId, positionMs, listen.sessionId)
    }

    override suspend fun progress(listen: Listen, positionMs: Long, paused: Boolean) {
        api(listen)?.reportPlaybackProgress(listen.trackId, positionMs, paused, listen.sessionId)
    }

    override suspend fun ended(listen: Listen, positionMs: Long) {
        api(listen)?.reportPlaybackStopped(listen.trackId, positionMs, listen.sessionId)
    }

    private suspend fun api(listen: Listen): JellyfinApi? =
        if (isJellyfinTrack(listen.trackId)) apiHolder.forId(listen.trackId) as? JellyfinApi else null
}

private fun isJellyfinTrack(songId: String): Boolean =
    ServerScope.serverOf(songId)?.let { serverId -> AppServerPrefs.servers.value.value.find { it.id == serverId }?.kind } == ServerKind.JELLYFIN
