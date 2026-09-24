package com.musicplus.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What the store needs of a server: the bytes of an item's cover art. [MusicApi] is the real one; a test supplies its own. */
interface CoverArtSource {
    /**
     * [coverArtId] is any item's own cover-art reference (not necessarily the item's own id), **scoped** (see [ServerScope]); the
     * adapter speaks the server's own id on the wire. It asks for [size] pixels, which a server may not honour (Navidrome answered a
     * request for 300 with a 1024x1024 WebP, confirmed 2026-09-18), so a caller decodes at the size it needs.
     */
    suspend fun coverArtBytes(coverArtId: String, size: Int = CoverArtStore.DEFAULT_SIZE): ByteArray
}

/**
 * Cover art as bytes: a scoped cover-art id and a size go in, the picture's bytes come out, from disk when the phone has them and
 * from the server when it does not. This is the whole of what crosses the seam to a server for art. Before, the adapters built a
 * fully authenticated URL (a fresh auth token per row, at mapping time, which was real measured cost on a several-thousand-row
 * library) and the repository parsed the id, the size and a server parameter back out of it, so the URL's shape was an unstated
 * interface between two modules, and Jellyfin's (id in the path, `maxWidth`) did not fit the parser: no Jellyfin art could ever load
 * (#66). Now the id is the interface and the URL is an internal detail of each adapter.
 *
 * Files are `<file key of the scoped id>-<size>.art`; this is the one place that knows that and the directory.
 *
 * **The stock picture.** A server answers a request for the art of an album it has none for with a stock "no artwork" picture, as an
 * ordinary successful answer, and hands the same picture to every such album. That used to be kept on disk forever like any other
 * art, so an album whose real art turned up on the server afterwards (found by a later scan, or fetched from an outside source that
 * had not answered the first time) kept showing the stock picture for good: "Stoney" showed Navidrome's blue vinyl while its songs,
 * whose art is a different id, showed the cover. A picture that is the same to the byte for more than one cached entry is that stock
 * picture, so [refreshedIfStock] asks for it again, at most once per [recheckAfterMs] per entry. Worked out once per process, from
 * the files on disk.
 */
class CoverArtStore(
    private val dir: File,
    private val sourceFor: suspend (coverArtId: String) -> CoverArtSource?,
    private val serverUnreachable: (serverId: String?) -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val recheckAfterMs: Long = NO_ART_RECHECK_MS,
    private val recheckTimeoutMs: Long = RECHECK_TIMEOUT_MS,
) {
    init {
        dir.mkdirs()
    }

    private val stockPictures: StockPictures by lazy {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        // Only files that share a byte size with another can be the same picture, so only those are read.
        val sameSize = files.groupBy { it.length() }.filterValues { it.size >= 2 }
        val counts = HashMap<String, Int>()
        for (group in sameSize.values) for (file in group) runCatching { counts.merge(sha256(file.readBytes()), 1, Int::plus) }
        StockPictures(sizes = sameSize.keys, digests = counts.filterValues { it >= 2 }.keys)
    }

    private class StockPictures(val sizes: Set<Long>, val digests: Set<String>)

    /** Entries already asked about again in this process (whatever the answer), so a slow or unreachable server is never asked twice for the same one. */
    private val recheckedKeys = ConcurrentHashMap.newKeySet<String>()
    private val recheckFailuresInARow = AtomicInteger(0)

    /**
     * Re-checks in progress, by entry. They run on the store's own scope, not the caller's: a list re-emits its rows and every row's
     * effect is restarted, which cancels the caller mid-request. A re-check tied to the caller was cancelled that way, counted as done,
     * and never finished. Whoever asks next waits for the one already running instead.
     */
    private val rechecksInProgress = ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val recheckScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most this many stock pictures are being asked about at once, so a slow answer does not hold up the art behind it. */
    private val recheckSlots = Semaphore(2)

    /** The art's bytes: from disk, else from the server, and kept on disk. Throws when its server is not set up, or cannot answer. */
    suspend fun bytes(coverArtId: String, size: Int = DEFAULT_SIZE): ByteArray =
        readFromDisk(coverArtId, size) ?: fetchFromNetwork(coverArtId, size)

    /**
     * If the copy on disk for this entry is the server's stock "no artwork" picture and it has not been asked about lately, asks the
     * server again and returns the answer (written to disk, and so timestamped, which is what spaces the checks out); null to carry
     * on with what is on disk.
     */
    suspend fun refreshedIfStock(coverArtId: String, size: Int = DEFAULT_SIZE): ByteArray? {
        val key = key(coverArtId, size)
        if (key in recheckedKeys) return null
        val running = rechecksInProgress.computeIfAbsent(key) {
            recheckScope.async {
                try {
                    recheck(coverArtId, size)
                } finally {
                    recheckedKeys += key
                    rechecksInProgress.remove(key)
                }
            }
        }
        return running.await()
    }

    private suspend fun recheck(coverArtId: String, size: Int): ByteArray? {
        // A server that is down, or that has been too slow to ask twice running, is left alone.
        if (serverUnreachable(ServerScope.serverOf(coverArtId))) return null
        if (recheckFailuresInARow.get() >= MAX_RECHECK_FAILURES) return null
        val file = fileFor(coverArtId, size)
        if (!file.exists() || nowMs() - file.lastModified() < recheckAfterMs) return null
        if (file.length() !in stockPictures.sizes || sha256(file.readBytes()) !in stockPictures.digests) return null
        return try {
            recheckSlots.withPermit {
                val fresh = withTimeoutOrNull(recheckTimeoutMs) { fetchFromNetwork(coverArtId, size) }
                if (fresh == null) recheckFailuresInARow.incrementAndGet() else recheckFailuresInARow.set(0)
                fresh
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recheckFailuresInARow.incrementAndGet()
            null
        }
    }

    private fun readFromDisk(coverArtId: String, size: Int): ByteArray? {
        val file = fileFor(coverArtId, size)
        return if (file.exists()) file.readBytes() else null
    }

    private suspend fun fetchFromNetwork(coverArtId: String, size: Int): ByteArray {
        val source = sourceFor(coverArtId) ?: throw IllegalStateException("no server configured")
        val bytes = source.coverArtBytes(coverArtId, size)
        // Best-effort: a disk-cache write failure should not fail the result.
        runCatching { fileFor(coverArtId, size).writeBytes(bytes) }
        return bytes
    }

    fun fileFor(coverArtId: String, size: Int): File = File(dir, "${ServerScope.fileKey(coverArtId)}-$size.art")

    /** Deletes the art kept for [serverId]. Files are named after the scoped id, so a server's are the ones starting with its file-safe scope. */
    fun deleteForServer(serverId: String) {
        val prefix = ServerScope.fileKey(ServerScope.scope(serverId, ""))
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /** Deletes everything. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun key(coverArtId: String, size: Int) = "$coverArtId:$size"

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** The directory the store keeps its files in, under the app's files dir. */
        const val DIR_NAME = "albumart"

        /** The size asked of a server: what the app shows art at, more or less. */
        const val DEFAULT_SIZE = 300

        /** How long a stock picture is trusted before the server is asked again. */
        const val NO_ART_RECHECK_MS = 12L * 60 * 60 * 1000

        /** How long a re-check waits for the server. Generous: the phone is often still busy syncing when the first art is asked for. */
        const val RECHECK_TIMEOUT_MS = 6_000L

        /** After this many re-checks that got no answer, none are tried for the rest of the process. */
        const val MAX_RECHECK_FAILURES = 2
    }
}
