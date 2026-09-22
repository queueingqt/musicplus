package com.musicplus.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches, decodes, and caches cover art. There's no image-loading library
 * allowlisted for this app (checked `LightSdkPlugin.ALLOWED_DEPENDENCIES` —
 * no Coil/Glide/etc.), so this does the same three steps one of those libraries
 * would do, by hand:
 *  1. fetch bytes via [SubsonicApi.coverArtBytes] — goes through the same Ktor/CIO
 *     client as every other network call in this app, so it isn't subject to
 *     Android's cleartext-traffic block the way an OkHttp/URLConnection-based
 *     image loader would be (see SubsonicClient's class doc).
 *  2. decode with [BitmapFactory] — pure Android SDK, no dependency needed.
 *  3. cache in memory and on disk, alongside PlaybackRepository's `streamcache/`
 *     directory convention.
 *
 * [Track]/[Album]/[Artist].coverArtUrl is already a fully-built, fully-authenticated
 * `getCoverArt.view` URL (see LibraryRepository.toDomain()) — its `id`/`size` query
 * params are parsed back out here so the actual byte fetch can go through
 * [SubsonicApi.coverArtBytes] (same id+size shape as [SubsonicApi.downloadBytes]/
 * [SubsonicApi.streamBytes]) rather than hitting the pre-built URL directly.
 *
 * **The memory cache is keyed by `coverArtId:size`, never by the raw URL.**
 * `coverArtUrl()` embeds a fresh Subsonic auth token/salt on every single call
 * (`SubsonicClient.authParams()` — correct, standard, prevents replay), so the
 * same artwork produces a *different* URL string every time it's requested,
 * even moments apart. An earlier version of this class cached by the full URL
 * and the memory cache could therefore never hit across recompositions or
 * screen revisits — confirmed live on-device 2026-09-18 via added logging
 * (every single "cache check" logged a miss, even for art fetched seconds
 * earlier), which is what produced a visible artwork→placeholder→artwork
 * flicker on every screen that showed previously-seen art. The disk cache was
 * always correctly keyed by `coverArtId`/`size` (see [diskCacheFile]), which is
 * why it masked the bug as "occasionally slow" rather than "always broken."
 */
class AlbumArtRepository(
    private val apiHolder: SubsonicApiHolder,
    filesDir: File,
) {
    // Reported live: after a long session touching many albums/artists/playlists,
    // scrolling got severely stuttery — "Slow UI thread" dominated the frame
    // stats (dumpsys gfxinfo), and the GPU texture cache had grown to tens of MB
    // across only a few dozen cached images. Root cause: this was an unbounded
    // ConcurrentHashMap that held every full decoded Bitmap ever fetched for the
    // life of the process, so a long session's worth of art accumulated forever,
    // driving up GC pressure (which stalls every thread, including Main) with no
    // ceiling. LruCache with a byte-size-aware sizeOf caps total memory instead —
    // 1/8th of the app's max heap is the standard Android sizing convention for
    // an in-memory bitmap cache (matches the guidance in Android's own bitmap
    // caching docs). Disk cache/network fetch are unaffected; this only bounds
    // what stays resident in memory.
    private val memoryCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // Permanent for the process's life, no TTL/retry — deliberately the
    // opposite of LyricsRepository's policy (a failed fetch there is never
    // cached, so it's retried on every call). Same-shaped interfaces
    // (getBitmap/getLyrics), opposite choice, previously signaled nowhere —
    // confirmed live, 2026-09-18 architecture review. The difference is
    // real, not an oversight: cover art is requested far more often than
    // lyrics (every row scrolled into view, vs. once per Now Playing visit),
    // so retrying a genuinely-broken id on every single scroll would hammer
    // a struggling/misconfigured server for art that's never going to
    // resolve; lyrics are rare enough that retrying is cheap and worth it
    // for a track whose fetch merely hit a transient network blip.
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val diskCacheDir = File(filesDir, "albumart").apply { mkdirs() }

    /**
     * A server answers a request for the art of an album it has none for with a stock "no artwork" picture, as an ordinary
     * successful answer, and it hands that same picture to every such album. That picture used to be kept on disk forever
     * like any other art, so an album whose real art turned up on the server afterwards (found by a later scan, or fetched
     * from an outside source that had not answered the first time) kept showing the stock picture for good: "Stoney" showed
     * Navidrome's blue vinyl while its songs, whose art is a different id, showed the cover. A picture that is the same
     * to the byte for more than one cached entry is that stock picture, so it is asked for again, at most once per
     * [NO_ART_RECHECK_MS] per entry. Computed once per process, from the files on disk.
     */
    private val stockPictures: StockPictures by lazy {
        val files = diskCacheDir.listFiles()?.filter { it.isFile }.orEmpty()
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
     * Re-checks in progress, by entry. They run on this repository's own scope, not the caller's: a list re-emits its rows
     * with fresh art URLs (each carries a new auth token) and every row's effect is restarted, which cancels the caller
     * mid-request. A re-check tied to the caller was cancelled that way, counted as done, and never finished. Whoever asks
     * next waits for the one already running instead.
     */
    private val rechecksInProgress = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val recheckScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most this many stock pictures are being asked about at once. Separate from [fetchMutex]: a slow answer must not hold up the art behind it. */
    private val recheckSlots = Semaphore(2)

    // A single mutex serializes fetches rather than one-lock-per-key: cover art
    // requests are small, infrequent relative to playback traffic, and bounding
    // concurrency to 1 avoids a fast-scrolling list firing a burst of simultaneous
    // requests at a self-hosted server on a LAN/tailnet. The memory-cache check
    // before AND after acquiring the lock means a request that lost a race to
    // fetch the same art doesn't redundantly re-fetch once the winner finishes.
    private val fetchMutex = Mutex()

    /** Synchronous, memory-cache-only lookup — lets a caller show an already-cached image on its very first composition instead of flashing a placeholder while [getBitmap] re-confirms the same cache hit. */
    fun peekCached(url: String): Bitmap? {
        val (coverArtId, size) = parseCoverArtParams(url) ?: return null
        return memoryCache.get(cacheKey(coverArtId, size))
    }

    /** Returns the decoded [Bitmap] for [url] (cached after the first successful fetch), or null if it's not fetchable/decodable. */
    suspend fun getBitmap(url: String): Bitmap? {
        val (coverArtId, size) = parseCoverArtParams(url) ?: return null
        val key = cacheKey(coverArtId, size)
        memoryCache.get(key)?.let { return it }
        if (key in failedKeys) return null
        // The server's stock "no artwork" picture on disk is asked about again before anything is shown: outside the lock
        // below, so a slow answer cannot hold up other art, and without ever putting the stock picture in memory first.
        recheckedStockPicture(coverArtId, size, key)?.let { return it }
        return fetchMutex.withLock {
            memoryCache.get(key)?.let { return@withLock it }
            if (key in failedKeys) return@withLock null
            fetchDecodeAndCache(coverArtId, size, key)
        }
    }

    /**
     * Dispatched to [Dispatchers.IO] — reported live as stuttery fast-scrolling
     * on the Albums list while artwork was still lazy-loading in. Root cause:
     * this whole function used to run on whatever dispatcher the caller's
     * coroutine was on, and every caller is `AlbumArt`'s own `LaunchedEffect`,
     * which is Main-confined (Compose UI coroutines). `File.readBytes()` and
     * `BitmapFactory.decodeByteArray()` are both synchronous, blocking calls —
     * a disk read plus a real image decode running directly on the UI thread
     * for every row that scrolled into view for the first time, with nothing
     * to shield the frame clock from it. Confirmed by reading the file: no
     * `Dispatchers`/`withContext` usage existed here at all before this fix.
     */
    private suspend fun fetchDecodeAndCache(coverArtId: String, size: Int, key: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = readFromDisk(coverArtId, size) ?: fetchFromNetwork(coverArtId, size)
                val bitmap = decodeSampled(bytes, size)
                if (bitmap == null) {
                    failedKeys += key
                    null
                } else {
                    memoryCache.put(key, bitmap)
                    bitmap
                }
            } catch (e: CancellationException) {
                // Not a real failure — the caller (AlbumArt's LaunchedEffect)
                // left composition mid-fetch (scrolled/navigated away), which
                // is routine, not an error. Reported live, 2026-09-18: a
                // plain `catch (e: Exception)` here also caught this and
                // permanently blacklisted the coverArtId in [failedKeys]
                // (no TTL/retry — see its own doc), so art that merely lost a
                // race with navigation once stayed missing for the rest of
                // the process's life, even on a perfectly healthy server.
                // Rethrown, not swallowed — suppressing CancellationException
                // breaks structured concurrency regardless of this bug.
                throw e
            } catch (e: Exception) {
                AppLogger.e("AlbumArtRepository", "fetchDecodeAndCache($coverArtId, $size) failed", e)
                failedKeys += key
                null
            }
        }

    /**
     * Decodes at roughly [targetSize] pixels on the larger side instead of
     * whatever the source bytes actually are. Confirmed live (2026-09-18) that
     * this server doesn't honor `getCoverArt.view`'s `size` request param at
     * all — a request for size=300 came back as a 1024x1024 WebP regardless
     * (pulled a real cached file off-device and checked its pixel dimensions
     * directly). Decoding that at full resolution is ~4MB per image
     * (1024*1024*4 bytes, ARGB_8888) for art displayed at a fraction of that
     * size — confirmed as the dominant cause of severe scroll stutter via
     * `dumpsys gfxinfo`'s frame stats (UI-thread-bound jank) and GPU texture
     * cache size (tens of MB across a few dozen images). Two-pass decode: read
     * bounds only first (`inJustDecodeBounds`, no pixel allocation), compute
     * the largest power-of-two `inSampleSize` that still comfortably covers
     * [targetSize], then decode for real at that reduced resolution. This is
     * a client-side mitigation for a server-side gap, not a workaround for
     * anything under this app's own control to fix directly.
     */
    private fun decodeSampled(bytes: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun cacheKey(coverArtId: String, size: Int) = "$coverArtId:$size"

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * If the cached picture for this entry is the server's stock "no artwork" picture (see [stockPictures]) and it has
     * not been asked about lately, asks the server again and returns the answer decoded, or null to carry on with what is
     * cached. The answer is written to disk (and so timestamped, which is what spaces the checks out).
     */
    private suspend fun recheckedStockPicture(coverArtId: String, size: Int, key: String): Bitmap? {
        if (key in recheckedKeys) return null
        val running = rechecksInProgress.computeIfAbsent(key) {
            recheckScope.async {
                try {
                    recheckStockPicture(coverArtId, size, key)
                } finally {
                    recheckedKeys += key
                    rechecksInProgress.remove(key)
                }
            }
        }
        return running.await()
    }

    private suspend fun recheckStockPicture(coverArtId: String, size: Int, key: String): Bitmap? {
        // A server that is down, or that has been too slow to ask twice running, is left alone.
        if (ServerScope.serverOf(coverArtId) in AppServerPrefs.unreachableServerIds.value.value) return null
        if (recheckFailuresInARow.get() >= MAX_RECHECK_FAILURES) return null
        val file = diskCacheFile(coverArtId, size)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() < NO_ART_RECHECK_MS) return null
        if (file.length() !in stockPictures.sizes || sha256(file.readBytes()) !in stockPictures.digests) return null
        return try {
            recheckSlots.withPermit {
                val fresh = withTimeoutOrNull(RECHECK_TIMEOUT_MS) { fetchFromNetwork(coverArtId, size) }
                if (fresh == null) {
                    recheckFailuresInARow.incrementAndGet()
                    return@withPermit null
                }
                recheckFailuresInARow.set(0)
                decodeSampled(fresh, size)?.also { memoryCache.put(key, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recheckFailuresInARow.incrementAndGet()
            null
        }
    }

    private fun readFromDisk(coverArtId: String, size: Int): ByteArray? {
        val file = diskCacheFile(coverArtId, size)
        return if (file.exists()) file.readBytes() else null
    }

    private suspend fun fetchFromNetwork(coverArtId: String, size: Int): ByteArray {
        val api = apiHolder.forId(coverArtId) ?: throw IllegalStateException("no server configured")
        val bytes = api.coverArtBytes(coverArtId, size)
        // Best-effort: a disk-cache write failure shouldn't fail the in-memory result.
        runCatching { diskCacheFile(coverArtId, size).writeBytes(bytes) }
        return bytes
    }

    private fun diskCacheFile(coverArtId: String, size: Int): File {
        return File(diskCacheDir, "${ServerScope.fileKey(coverArtId)}-$size.art")
    }

    /**
     * Pulls the id and size back out of a URL built by [SubsonicApi.coverArtUrl] — see the class doc for why.
     * The id comes back scoped: the URL carries the server's own id plus which server it is for. A URL without
     * that (one saved before servers were told apart) says nothing about whose art it is, so it is not fetched.
     */
    private fun parseCoverArtParams(url: String): Pair<String, Int>? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        var id: String? = null
        var size: Int? = null
        var server: String? = null
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@forEach
            val key = pair.substring(0, eq)
            val rawValue = pair.substring(eq + 1)
            val value = try {
                java.net.URLDecoder.decode(rawValue, "UTF-8")
            } catch (e: Exception) {
                return@forEach
            }
            when (key) {
                "id" -> id = value
                "size" -> size = value.toIntOrNull()
                SubsonicApi.COVER_ART_SERVER_PARAM -> server = value
            }
        }
        val resolvedId = id
        val resolvedSize = size
        val resolvedServer = server
        return if (resolvedId != null && resolvedSize != null && resolvedServer != null) {
            ServerScope.scope(resolvedServer, resolvedId) to resolvedSize
        } else {
            null
        }
    }

    private companion object {
        /** How long a stock picture is trusted before the server is asked again. */
        const val NO_ART_RECHECK_MS = 12L * 60 * 60 * 1000

        /** How long a re-check waits for the server. Generous: the phone is often still busy syncing when the first art is asked for. */
        const val RECHECK_TIMEOUT_MS = 6_000L

        /** After this many re-checks that got no answer, none are tried for the rest of the process. */
        const val MAX_RECHECK_FAILURES = 2
    }
}
