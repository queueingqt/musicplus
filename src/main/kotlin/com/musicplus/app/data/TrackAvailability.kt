package com.musicplus.app.data

import com.musicplus.app.Track
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Whether a song can be played right now. A song whose server is off or cannot be reached is *unavailable*, unless the
 * phone has the audio itself: a finished download, or a copy already kept from streaming it. Those play with no server
 * at all, so they never grey out.
 *
 * Lists grey an unavailable song out and say "Server not reachable" when it is tapped; playback skips one in a queue.
 */
object TrackAvailability {
    @Volatile private var filesDir: File? = null

    /**
     * Names of the files in `streamcache`, refreshed in the background at most once per [STREAM_CACHE_LISTING_TTL_MS].
     * [streamCopy] is called from Compose recomposition and from [PlaybackRepository]'s own `Dispatchers.Main.immediate`
     * scope — both places that must never block on disk I/O — so a stale listing is read synchronously while a fresh one is
     * fetched on [refreshScope], not the other way around. Confirmed live, 2026-09-21: the first version called
     * `File.list()` (a blocking directory read) straight from those callers.
     */
    @Volatile private var streamCacheNames: Set<String> = emptySet()
    @Volatile private var streamCacheReadAt = 0L
    private val refreshing = AtomicBoolean(false)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(filesDir: File) {
        this.filesDir = filesDir
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

    /** The copy of the song kept from streaming it, if there is one. The stream cache names a file `<song key>-<quality>.mp3`; any quality will do to play the song. */
    fun streamCopy(songId: String): File? {
        val dir = filesDir?.let { File(it, "streamcache") } ?: return null
        if (System.currentTimeMillis() - streamCacheReadAt > STREAM_CACHE_LISTING_TTL_MS) refreshStreamCacheListing(dir)
        val prefix = "${ServerScope.fileKey(songId)}-"
        return streamCacheNames.firstOrNull { it.startsWith(prefix) && it.endsWith(".mp3") }?.let { File(dir, it) }?.takeIf { it.isFile }
    }

    /** Fire-and-forget: at most one listing in flight at a time. The caller reads whatever is already cached, however stale, and never waits on this. */
    private fun refreshStreamCacheListing(dir: File) {
        if (!refreshing.compareAndSet(false, true)) return
        refreshScope.launch {
            try {
                streamCacheNames = dir.list()?.toHashSet() ?: emptySet()
                streamCacheReadAt = System.currentTimeMillis()
            } finally {
                refreshing.set(false)
            }
        }
    }

    private const val STREAM_CACHE_LISTING_TTL_MS = 5_000L
}
