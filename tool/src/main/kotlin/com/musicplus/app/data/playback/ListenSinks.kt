package com.musicplus.app.data.playback

import com.musicplus.app.data.ApiLookup
import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.AppScrobblePrefs
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.Capabilities
import com.musicplus.app.data.Capability
import com.musicplus.app.data.ServerScope

/** Whether [serverId] is on and offers scrobbling. Read from the warmed copies, so it can be asked from anywhere. */
private fun serverCanScrobble(serverId: String?): Boolean =
    serverId != null && serverId in AppServerPrefs.enabledServerIds.value.value && Capabilities.can(serverId, Capability.SCROBBLE)

/**
 * Counts a listen on a server that offers scrobbling. Off by default (see `AppSettingsRepository.scrobblingEnabled`): "now
 * playing" (submission=false) when a listen starts, the real scrobble (submission=true) once it counts. Failures are logged and
 * surfaced to [AppScrobblePrefs.lastError] (cleared on the next success), never swallowed.
 *
 * What a server takes for this is asked of its api ([com.musicplus.app.data.MusicApi.scrobbler]), not found out by casting to a
 * backend: an api that offers none (Jellyfin has no equivalent of a `scrobble.view` relay) is simply not a target.
 */
class ScrobbleSink(
    private val apis: ApiLookup,
    /** The exact same song (same artist, title and album) on other servers, for when its own cannot count it. */
    private val sameSongElsewhere: suspend (songId: String) -> List<String>,
    private val canScrobbleOn: (serverId: String?) -> Boolean = ::serverCanScrobble,
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
        val scrobbler = apis.forId(target)?.scrobbler ?: return
        try {
            scrobbler.scrobble(target, submission)
            AppLogger.d(TAG, "scrobble(songId=$songId, submission=$submission) succeeded" + if (target != songId) " on another server, as $target" else "")
            AppScrobblePrefs.lastError.set(null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "scrobble(songId=$songId, submission=$submission) failed", e)
            AppScrobblePrefs.lastError.set(e.message ?: "Scrobble failed")
        }
    }

    /**
     * Which song to scrobble, so the play is counted somewhere: the song itself when its own server is on and offers
     * scrobbling, otherwise the exact same song on another server that does, otherwise none (the play is not counted).
     */
    private suspend fun scrobbleTarget(songId: String): String? {
        if (canScrobbleOn(ServerScope.serverOf(songId))) return songId
        return sameSongElsewhere(songId).firstOrNull { canScrobbleOn(ServerScope.serverOf(it)) }
    }

    private companion object {
        const val TAG = "ScrobbleSink"
    }
}

/**
 * Reports playback to a server that keeps a resume position and play history ([com.musicplus.app.data.MusicApi.playReporter],
 * Jellyfin's own reporting): unconditional, never gated by the scrobbling setting, because it drives that server's own resume position
 * and play history and has nothing to do with a Last.fm relay. Start when a listen begins, Progress at the [ListenReporting] interval
 * for as long as it is current (playing or paused, so a pause still saves roughly where playback left off), Stopped once it is not.
 * A song whose server offers no such reporting is not this sink's business.
 */
class PlayReportSink(private val apis: ApiLookup) : ListenSink {
    override suspend fun wantsProgress(listen: Listen): Boolean = reporter(listen) != null

    override suspend fun started(listen: Listen, positionMs: Long) {
        reporter(listen)?.reportPlaybackStart(listen.trackId, positionMs, listen.sessionId)
    }

    override suspend fun progress(listen: Listen, positionMs: Long, paused: Boolean) {
        reporter(listen)?.reportPlaybackProgress(listen.trackId, positionMs, paused, listen.sessionId)
    }

    override suspend fun ended(listen: Listen, positionMs: Long) {
        reporter(listen)?.reportPlaybackStopped(listen.trackId, positionMs, listen.sessionId)
    }

    private suspend fun reporter(listen: Listen) = apis.forId(listen.trackId)?.playReporter
}
