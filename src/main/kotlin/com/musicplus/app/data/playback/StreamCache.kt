package com.musicplus.app.data.playback

import com.musicplus.app.data.AppLogger
import com.musicplus.app.data.FetchGate
import com.musicplus.app.data.ServerScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The songs kept from playing them: one directory, one naming rule, one place that knows both.
 *
 * A finished copy is `<file key of the scoped song id>-<kbps|orig>.mp3` ([file]); the quality is part of the name so a song cached
 * at one quality is not mistaken for "already have it at the quality wanted now" after switching networks. The same song can hold
 * more than one copy over time, an accepted cost: there is no eviction, and "Clear all local data" is the manual escape hatch.
 * Before this module the name was written by the playback code and matched by four other modules (availability, integrity, server
 * removal, the id migration), and the directory literal appeared at eight code sites in six files.
 *
 * [fetch] keeps one invariant for every caller: **a file at a copy's final path is always a finished one.** It is written under
 * `<name>.part`, moved into place atomically, and cleaned up on any failure. Before that (issue #47) a second caller arriving
 * mid-download saw "already cached" on a half-written file, or started a second download into the same file, and a cancelled
 * download deleted whatever was at that path. One download per file at a time, guarded by a per-name mutex.
 */
class StreamCache(
    private val dir: File,
    private val listingTtlMs: Long = LISTING_TTL_MS,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Where the finished copy of [songId] at [maxBitRateKbps] (null: the original) lives. Whether it exists is [has]. */
    fun file(songId: String, maxBitRateKbps: Int?): File = File(dir, "${ServerScope.fileKey(songId)}-${maxBitRateKbps ?: ORIGINAL}.mp3")

    fun has(songId: String, maxBitRateKbps: Int?): Boolean = file(songId, maxBitRateKbps).exists()

    /**
     * A path that never exists, for a song that cannot be played (its server is off or unreachable and the phone has no copy).
     * Handed to the player so the song sits in the queue as an item that errors and is skipped, rather than stalling the whole
     * queue build on a fetch that cannot succeed.
     */
    fun placeholder(songId: String): File = File(dir, "${ServerScope.fileKey(songId)}-unreachable")

    // Names of the files in the directory, refreshed in the background at most once per listing TTL. [anyCopy] is called from
    // Compose recomposition and from the playback scope (Main.immediate), both places that must never block on disk I/O, so a
    // stale listing is read synchronously while a fresh one is fetched, not the other way around (confirmed live 2026-09-21: the
    // first version called File.list() straight from those callers).
    @Volatile private var names: Set<String> = emptySet()
    @Volatile private var listedAtMs = 0L
    private val refreshing = AtomicBoolean(false)
    private val _revision = MutableStateFlow(0)

    /**
     * Changes whenever the set of copies the listing knows of changes. Because the listing is read in the background, whoever asked
     * [anyCopy] first may have been told "no copy" by a listing that had not been read yet (a song greyed out as unavailable while
     * its copy sat in the cache, #76); it asks again when this changes.
     */
    val revision: StateFlow<Int> = _revision

    init {
        // Read now, not at the first question: the first row to ask used to be answered from an empty listing.
        refreshListingInBackground()
    }

    /** A finished copy of [songId] at any quality, or null. Never blocks on disk (see above); briefly stale by design. */
    fun anyCopy(songId: String): File? {
        if (System.currentTimeMillis() - listedAtMs > listingTtlMs) refreshListingInBackground()
        val prefix = "${ServerScope.fileKey(songId)}-"
        // The rest of the name must be a quality and the extension: an id that itself contains a dash must not match another song's copy.
        val name = names.firstOrNull { it.startsWith(prefix) && COPY_SUFFIX.matches(it.removePrefix(prefix)) } ?: return null
        return File(dir, name).takeIf { it.isFile }
    }

    private fun refreshListingInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                refreshListingNow()
            } finally {
                refreshing.set(false)
            }
        }
    }

    internal fun refreshListingNow() {
        val listed = dir.list()?.toHashSet() ?: emptySet()
        listedAtMs = System.currentTimeMillis()
        if (listed != names) {
            names = listed
            _revision.update { it + 1 }
        }
    }

    /**
     * The finished copy of [songId] at [maxBitRateKbps], fetching it first if it is not there. [download] writes the audio into the
     * `.part` file it is given, holding the [FetchGate] lease it is given (so the gate orders and paces every fetch). Callers
     * arriving while a fetch of the same copy runs wait for it instead of starting a second one.
     */
    suspend fun fetch(
        songId: String,
        maxBitRateKbps: Int?,
        rank: () -> FetchGate.Priority,
        download: suspend (part: File, lease: FetchGate.Lease) -> Unit,
    ): File {
        val cached = file(songId, maxBitRateKbps)
        if (cached.exists()) {
            AppLogger.d(TAG, "cachedStreamFile($songId): already cached")
            return cached
        }
        dir.mkdirs()
        return locks.getOrPut(cached.name) { Mutex() }.withLock {
            if (cached.exists()) {
                AppLogger.d(TAG, "cachedStreamFile($songId): already cached")
                return@withLock cached
            }
            AppLogger.d(TAG, "cachedStreamFile($songId): not cached, downloading")
            val part = File(dir, "${cached.name}.part")
            try {
                val fetched = FetchGate.run(FetchGate.Lane.PLAYBACK, songId, rank, transcoding = maxBitRateKbps != null) { lease -> download(part, lease) }
                if (fetched == null) throw IOException("the connection is busy with other transfers")
                if (!part.renameTo(cached)) throw IOException("could not move ${cached.name}.part into place")
                // Told to the listing directly rather than re-read, since this may run on the main thread; a refresh that raced it and
                // missed the file finds it on the next one.
                names = names + cached.name
                _revision.update { it + 1 }
                AppLogger.d(TAG, "cachedStreamFile($songId): write complete")
            } catch (e: Exception) {
                // A failed, interrupted or cancelled fetch only ever leaves its own .part, never something at the final path that
                // the exists() check would treat as a cache hit: that is what used to play back a corrupt partial file.
                part.delete()
                throw e
            }
            cached
        }
    }

    /** Deletes every copy (and leftover part) kept for songs of [serverId]. Files are named after the scoped id, so a server's are the ones starting with its file-safe scope. */
    fun deleteForServer(serverId: String) {
        val prefix = ServerScope.fileKey(ServerScope.scope(serverId, ""))
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        names = names.filterNot { it.startsWith(prefix) }.toSet()
        _revision.update { it + 1 }
        listedAtMs = 0L
    }

    /** Deletes everything. */
    fun clear() {
        dir.deleteRecursively()
        names = emptySet()
        _revision.update { it + 1 }
        listedAtMs = 0L
    }

    /** Deletes `.part` files older than [olderThanMs]: leftovers of fetches that were killed. A younger one may belong to a fetch running right now. Returns how many went. */
    fun sweepStaleParts(olderThanMs: Long): Int {
        val now = System.currentTimeMillis()
        var swept = 0
        dir.listFiles()?.filter { it.name.endsWith(".part") && now - it.lastModified() > olderThanMs }?.forEach { if (it.delete()) swept++ }
        return swept
    }

    companion object {
        private const val TAG = "StreamCache"
        private const val ORIGINAL = "orig"
        private const val LISTING_TTL_MS = 5_000L
        private val COPY_SUFFIX = Regex("^(\\d+|$ORIGINAL)\\.mp3$")
    }
}
