package com.musicplus.app.data.playback

import com.musicplus.app.Track
import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.FetchGate
import com.musicplus.app.data.ServerScope
import com.musicplus.app.data.playerCanFetch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** The two things the app asks of a server to have a song played: a URL the player can fetch, and the same bytes through the app's own client. */
interface SongStreams {
    /** Direct playback URL, fully authenticated, transcoded to [maxBitRateKbps] when given. Only safe to hand to the player where [playerCanFetch] says so. */
    fun streamUrl(songId: String, maxBitRateKbps: Int? = null): String

    /** Same content as [streamUrl], streamed to [destination] through the app's own HTTP client: the download-then-play path and the capped-quality download path. */
    suspend fun streamToFile(songId: String, destination: File, maxBitRateKbps: Int? = null, lease: FetchGate.Lease? = null)
}

/** What the player is handed for one song, and what that means for the person listening to it. */
sealed interface SongSource {
    /** Whether the player can seek in it. */
    val seekable: Boolean

    /**
     * A finished file: a download, a copy kept from playing it before, one fetched just now, or, for a song that cannot be played, a
     * copy at another quality or a path that never exists (see [StreamCache.placeholder]).
     */
    data class OnPhone(val file: File) : SongSource {
        override val seekable get() = true
    }

    /**
     * The player fetches [url] itself, so audio starts at once. A transcode has no length until it has been read to the end, so the
     * player cannot seek in it; an original file is served whole, with a length.
     */
    data class Streamed(val url: String, val transcoded: Boolean) : SongSource {
        override val seekable get() = !transcoded
    }
}

/**
 * Decides what the player is given for a song: the one place that knows the phone's copies, the server's state, the connection and
 * the quality being asked for, and therefore the only one that can say "file or stream, and can it seek".
 *
 * The policy, the same for every server and both URL schemes:
 * - a download whose file is really there plays from it, whatever state its server is in;
 * - a song whose server is off or unreachable plays from any copy the cache has, or from a stand-in that errors so the queue skips
 *   it, rather than stalling the whole queue build on a fetch that cannot succeed;
 * - a song already in the cache plays from it, so a replay starts at once and works offline;
 * - a song that is not cached is **streamed** when the player can fetch its URL and it is the one being started ([resolveStart]) or
 *   lies beyond the songs worth fetching ahead ([resolveQueue]), and otherwise **fetched** into the cache first.
 * A streamed song's file is still fetched by the caller's hand-over (see PlaybackRepository.extendToFullQueue), which is what makes
 * it seekable and keeps it for next time.
 *
 * The quality cap, the connection and [canStream] are each read once per call, so every song of one queue is decided against the same
 * answers. Before this, the decision was made in `toAudioItem` and re-derived, against connectivity read at a different instant, in
 * `play`, `noteStreams` and `prefetchRange` (#59).
 */
class TrackSources(
    private val cache: StreamCache,
    /**
     * The api for the server that owns the song. A queue can hold songs from more than one server (one saved before a server switch
     * keeps playing from the server it came from), so this is per song, not per queue. Null when that server was removed: its
     * downloaded songs still play, from their files, and only a song that has to be streamed or fetched needs the api.
     */
    private val apiFor: suspend (Track) -> SongStreams?,
    private val serverUsable: (serverId: String?) -> Boolean,
    /** How urgently a song is wanted right now, or null when it is not in the queue at all. Read by [FetchGate] as it schedules. */
    private val priorityOf: (songId: String) -> FetchGate.Priority?,
    /** The bitrate cap for the connection in use right now; null asks for the original. */
    private val streamKbps: suspend () -> Int?,
    private val onWifi: () -> Boolean,
    /** Whether the player itself may fetch this URL. Asked of the platform (see [playerCanFetch]); a parameter so it can be answered in a test. */
    private val canStream: (url: String) -> Boolean = ::playerCanFetch,
) {
    /** The song a play starts from: fetched at the top priority, or streamed when it is not cached, so audio begins at once. */
    suspend fun resolveStart(track: Track): SongSource =
        resolve(track, streamKbps(), streamIfUncached = true) { FetchGate.Priority.NOW_PLAYING }

    /**
     * Every song of [tracks], with the one playing at [current]. Whatever is not cached and is worth fetching ahead is fetched
     * concurrently (in the order [FetchGate] serves them, and only as many at a time as the connection can take); the rest is
     * handed over to stream. Null when [stillWanted] turned false, i.e. a newer play superseded this one. Any song failing fails the
     * whole call.
     *
     * Worth fetching ahead: on Wi-Fi every song. Off Wi-Fi only the playing song and the next [UP_NEXT_COUNT]: a whole queue is far
     * more than a cellular link can fetch in the time the playing song lasts (about 160 s for seven songs at 0.22 MB/s on the phone,
     * so the player had nothing after the first one), and fetches running beside a streaming song starve it.
     */
    suspend fun resolveQueue(tracks: List<Track>, current: Int, stillWanted: () -> Boolean = { true }): List<SongSource>? {
        val kbps = streamKbps()
        val fetchAhead = if (onWifi()) tracks.indices else current..(current + UP_NEXT_COUNT)
        val sources = arrayOfNulls<SongSource>(tracks.size)
        coroutineScope {
            for (index in tracks.indices) {
                launch {
                    if (!stillWanted()) return@launch
                    val track = tracks[index]
                    sources[index] = resolve(track, kbps, streamIfUncached = index !in fetchAhead) { priorityOf(track.id) ?: FetchGate.Priority.QUEUE }
                }
            }
        }
        if (!stillWanted()) return null
        return sources.map { it!! }
    }

    private suspend fun resolve(track: Track, kbps: Int?, streamIfUncached: Boolean, rank: () -> FetchGate.Priority): SongSource {
        val id = track.id
        // The queue keeps the Track it was built (or restored) with, so a download removed afterwards leaves localFilePath pointing at
        // a file that is gone; jumping back to that song then failed with ERROR_CODE_IO_FILE_NOT_FOUND (seen on the phone,
        // 2026-09-19). Trust the path only while the file is really there.
        val downloaded = track.localFilePath?.let { File(it) }?.takeIf { it.isFile }
        val source = when {
            downloaded != null -> SongSource.OnPhone(downloaded)
            !serverUsable(ServerScope.serverOf(id)) -> SongSource.OnPhone(cache.anyCopy(id) ?: cache.placeholder(id))
            else -> {
                val api = apiFor(track) ?: throw IOException("the server for \"${track.title}\" is not set up")
                val url = if (streamIfUncached && !cache.has(id, kbps)) api.streamUrl(id, kbps).takeIf(canStream) else null
                if (url != null) {
                    AppLogger.d(TAG, "resolve($id): not cached, streaming it")
                    SongSource.Streamed(url, transcoded = kbps != null)
                } else {
                    SongSource.OnPhone(cache.fetch(id, kbps, rank) { part, lease -> api.streamToFile(id, part, kbps, lease) })
                }
            }
        }
        AppLogger.d(TAG, "resolve($id): ${source.describe()} (downloaded=${downloaded != null}, serverUsable=${serverUsable(ServerScope.serverOf(id))})")
        return source
    }

    private fun SongSource.describe() = when (this) {
        is SongSource.OnPhone -> "OnPhone ${file.name}"
        is SongSource.Streamed -> "Streamed (${if (transcoded) "transcoded, not seekable" else "original"})"
    }

    companion object {
        private const val TAG = "TrackSources"

        /** How many songs after the playing one count as "up next": fetched ahead off Wi-Fi, and served next by [FetchGate]. */
        const val UP_NEXT_COUNT = 3
    }
}
