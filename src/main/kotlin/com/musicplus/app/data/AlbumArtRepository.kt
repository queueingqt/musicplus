package com.musicplus.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes and caches cover art in memory. There's no image-loading library allowlisted for this app (checked
 * `LightSdkPlugin.ALLOWED_DEPENDENCIES`, no Coil/Glide/etc.), so this does the last two steps one of those libraries would do, by hand:
 * decode with [BitmapFactory] (pure Android SDK, no dependency needed) and keep the result in an [LruCache]. The first step, getting the
 * picture's bytes (from disk, else from the server through the same Ktor/CIO client as every other network call, so it is not subject
 * to Android's cleartext-traffic block the way an image loader built on HttpURLConnection would be), is [CoverArtStore]'s.
 *
 * Art is asked for by its scoped cover-art id ([Track]/[Album]/[Artist].coverArtId), and the memory cache is keyed by it and the size.
 * It used to be keyed by the full URL, and each URL embedded a fresh auth token, so the same artwork was a different key every time and
 * the memory cache could never hit across recompositions or screen revisits (confirmed live 2026-09-18, which produced an
 * artwork, placeholder, artwork flicker on every screen that showed previously-seen art).
 */
class AlbumArtRepository(
    apiHolder: ApiLookup,
    filesDir: File,
    private val store: CoverArtStore = CoverArtStore(
        dir = File(filesDir, CoverArtStore.DIR_NAME),
        sourceFor = { apiHolder.forId(it) },
        serverUnreachable = { it in AppServerPrefs.unreachableServerIds.value.value },
    ),
) {
    // Reported live: after a long session touching many albums/artists/playlists, scrolling got severely stuttery: "Slow UI thread"
    // dominated the frame stats (dumpsys gfxinfo), and the GPU texture cache had grown to tens of MB across only a few dozen cached
    // images. Root cause: this was an unbounded ConcurrentHashMap that held every full decoded Bitmap ever fetched for the life of
    // the process, driving up GC pressure (which stalls every thread, including Main) with no ceiling. LruCache with a byte-size-aware
    // sizeOf caps total memory instead: 1/8th of the app's max heap is the standard Android sizing convention for an in-memory
    // bitmap cache. The disk cache and network fetch are unaffected; this only bounds what stays resident in memory.
    private val memoryCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // Permanent for the process's life, no TTL/retry: deliberately the opposite of LyricsRepository's policy (a failed fetch there is
    // never cached, so it is retried on every call). The difference is real, not an oversight: cover art is requested far more often
    // than lyrics (every row scrolled into view, vs. once per Now Playing visit), so retrying a genuinely broken id on every scroll
    // would hammer a struggling or misconfigured server for art that is never going to resolve; lyrics are rare enough that retrying
    // is cheap and worth it for a track whose fetch merely hit a transient network blip.
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    // A single mutex serializes fetches rather than one lock per key: cover art requests are small, infrequent relative to playback
    // traffic, and bounding concurrency to 1 avoids a fast-scrolling list firing a burst of simultaneous requests at a self-hosted
    // server on a LAN/tailnet. The memory-cache check before AND after acquiring the lock means a request that lost a race to fetch the
    // same art does not redundantly re-fetch once the winner finishes.
    private val fetchMutex = Mutex()

    /** Synchronous, memory-cache-only lookup: lets a caller show an already-cached image on its very first composition instead of flashing a placeholder while [getBitmap] re-confirms the same cache hit. */
    fun peekCached(coverArtId: String): Bitmap? = memoryCache.get(cacheKey(coverArtId))

    /** Returns the decoded [Bitmap] for [coverArtId] (cached after the first successful fetch), or null if it is not fetchable or decodable. */
    suspend fun getBitmap(coverArtId: String): Bitmap? {
        val key = cacheKey(coverArtId)
        memoryCache.get(key)?.let { return it }
        if (key in failedKeys) return null
        // The server's stock "no artwork" picture on disk is asked about again before anything is shown: outside the lock below, so a
        // slow answer cannot hold up other art, and without ever putting the stock picture in memory first.
        store.refreshedIfStock(coverArtId, SIZE)?.let { fresh ->
            decodeToMemory(fresh, key)?.let { return it }
        }
        return fetchMutex.withLock {
            memoryCache.get(key)?.let { return@withLock it }
            if (key in failedKeys) return@withLock null
            fetchDecodeAndCache(coverArtId, key)
        }
    }

    /** Deletes the art kept for [serverId] (its files on disk; what is in memory ages out). */
    fun deleteForServer(serverId: String) = store.deleteForServer(serverId)

    /** Deletes every picture kept on disk and forgets the ones in memory. */
    fun clear() {
        store.clear()
        memoryCache.evictAll()
        failedKeys.clear()
    }

    /**
     * Dispatched to [Dispatchers.IO]: reported live as stuttery fast-scrolling on the Albums list while artwork was still lazy-loading in.
     * Every caller is `AlbumArt`'s own `LaunchedEffect`, which is Main-confined, and `File.readBytes()` and
     * `BitmapFactory.decodeByteArray()` are synchronous, blocking calls: a disk read plus a real image decode on the UI thread for every
     * row that scrolled into view for the first time, with nothing to shield the frame clock from it.
     */
    private suspend fun fetchDecodeAndCache(coverArtId: String, key: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                decodeToMemory(store.bytes(coverArtId, SIZE), key).also { if (it == null) failedKeys += key }
            } catch (e: CancellationException) {
                // Not a real failure: the caller (AlbumArt's LaunchedEffect) left composition mid-fetch (scrolled or navigated away),
                // which is routine, not an error. A plain `catch (e: Exception)` here also caught this and permanently blacklisted the id
                // in [failedKeys] (reported live, 2026-09-18), so art that merely lost a race with navigation stayed missing for the
                // rest of the process's life, even on a perfectly healthy server. Rethrown, not swallowed: suppressing
                // CancellationException breaks structured concurrency regardless.
                throw e
            } catch (e: Exception) {
                AppLogger.e("AlbumArtRepository", "fetching cover art $coverArtId failed", e)
                failedKeys += key
                null
            }
        }

    private suspend fun decodeToMemory(bytes: ByteArray, key: String): Bitmap? = withContext(Dispatchers.IO) {
        decodeSampled(bytes, SIZE)?.also { memoryCache.put(key, it) }
    }

    /**
     * Decodes at roughly [targetSize] pixels on the larger side instead of whatever the source bytes actually are. Confirmed live
     * (2026-09-18) that this server does not honor `getCoverArt.view`'s `size` request param at all: a request for size=300 came back
     * as a 1024x1024 WebP regardless (a real cached file pulled off-device and its pixel dimensions checked directly). Decoding that
     * at full resolution is ~4MB per image (1024*1024*4 bytes, ARGB_8888) for art displayed at a fraction of that size, confirmed as the
     * dominant cause of severe scroll stutter via `dumpsys gfxinfo`'s frame stats and GPU texture cache size. Two-pass decode: read
     * bounds only first (`inJustDecodeBounds`, no pixel allocation), compute the largest power-of-two `inSampleSize` that still
     * comfortably covers [targetSize], then decode for real at that reduced resolution. A client-side mitigation for a server-side gap.
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

    private fun cacheKey(coverArtId: String) = "$coverArtId:$SIZE"

    private companion object {
        const val SIZE = CoverArtStore.DEFAULT_SIZE
    }
}
