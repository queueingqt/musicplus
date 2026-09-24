package com.musicplus.app.data

import com.musicplus.app.Track
import com.musicplus.app.data.playback.StreamCache
import java.io.File

/**
 * Whether a song can be played right now. A song whose server is off or cannot be reached is *unavailable*, unless the
 * phone has the audio itself: a finished download, or a copy already kept from streaming it. Those play with no server
 * at all, so they never grey out.
 *
 * Lists grey an unavailable song out and say "Server not reachable" when it is tapped; playback skips one in a queue.
 */
object TrackAvailability {
    @Volatile private var cache: StreamCache? = null

    fun init(cache: StreamCache) {
        this.cache = cache
    }

    /** True when [serverId] is on and was reachable the last time it was asked. */
    fun serverUsable(serverId: String?): Boolean =
        serverId == null ||
            // Nothing is known about any server yet (the saved list has not been read): assume it is fine rather than grey everything out.
            AppServerPrefs.servers.value.value.isEmpty() ||
            (serverId in AppServerPrefs.enabledServerIds.value.value && serverId !in AppServerPrefs.unreachableServerIds.value.value)

    /** True when the phone holds the song's audio: a finished download whose file is there, or a streamed copy kept in the cache. */
    fun hasAudioOnPhone(track: Track): Boolean = hasDownload(track) || streamCopy(track.id) != null

    fun isPlayable(track: Track): Boolean = serverUsable(ServerScope.serverOf(track.id)) || hasAudioOnPhone(track)

    private fun hasDownload(track: Track): Boolean =
        track.downloadStatus == DownloadStatus.COMPLETE && track.localFilePath?.let { File(it).isFile } == true

    /** The copy of the song kept from streaming it, at any quality, if there is one. */
    fun streamCopy(songId: String): File? = cache?.anyCopy(songId)
}
