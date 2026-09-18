package com.musicplus.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val diskCacheDir = File(filesDir, "albumart").apply { mkdirs() }

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
        return memoryCache[cacheKey(coverArtId, size)]
    }

    /** Returns the decoded [Bitmap] for [url] (cached after the first successful fetch), or null if it's not fetchable/decodable. */
    suspend fun getBitmap(url: String): Bitmap? {
        val (coverArtId, size) = parseCoverArtParams(url) ?: return null
        val key = cacheKey(coverArtId, size)
        memoryCache[key]?.let { return it }
        if (key in failedKeys) return null
        return fetchMutex.withLock {
            memoryCache[key]?.let { return@withLock it }
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
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    failedKeys += key
                    null
                } else {
                    memoryCache[key] = bitmap
                    bitmap
                }
            } catch (e: Exception) {
                AppLogger.e("AlbumArtRepository", "fetchDecodeAndCache($coverArtId, $size) failed", e)
                failedKeys += key
                null
            }
        }

    private fun cacheKey(coverArtId: String, size: Int) = "$coverArtId:$size"

    private fun readFromDisk(coverArtId: String, size: Int): ByteArray? {
        val file = diskCacheFile(coverArtId, size)
        return if (file.exists()) file.readBytes() else null
    }

    private suspend fun fetchFromNetwork(coverArtId: String, size: Int): ByteArray {
        val api = apiHolder.get() ?: throw IllegalStateException("no server configured")
        val bytes = api.coverArtBytes(coverArtId, size)
        // Best-effort: a disk-cache write failure shouldn't fail the in-memory result.
        runCatching { diskCacheFile(coverArtId, size).writeBytes(bytes) }
        return bytes
    }

    private fun diskCacheFile(coverArtId: String, size: Int): File {
        val safeId = coverArtId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(diskCacheDir, "$safeId-$size.art")
    }

    /** Pulls `id`/`size` back out of a URL built by [SubsonicApi.coverArtUrl] — see the class doc for why. */
    private fun parseCoverArtParams(url: String): Pair<String, Int>? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        var id: String? = null
        var size: Int? = null
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
            }
        }
        val resolvedId = id
        val resolvedSize = size
        return if (resolvedId != null && resolvedSize != null) resolvedId to resolvedSize else null
    }
}
