package com.musicplus.app.data

import com.musicplus.app.LyricLine
import com.musicplus.app.LyricsState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class CachedLyrics(
    val kind: String, // "synced" | "plain" | "none"
    val lines: List<CachedLine> = emptyList(),
    val text: String = "",
)

@Serializable
private data class CachedLine(val startMs: Long?, val text: String)

private fun CachedLyrics.toState(): LyricsState = when (kind) {
    "synced" -> LyricsState.Synced(lines.map { LyricLine(it.startMs, it.text) })
    "plain" -> LyricsState.Plain(text)
    else -> LyricsState.NoLyrics
}

/**
 * Fetches and disk-caches lyrics — same disk-cache-then-network shape as
 * [AlbumArtRepository], so lyrics for a track already viewed once (not just
 * one whose audio is downloaded) stay available offline afterward, matching
 * how album art already works. Reported live: lyrics had zero caching, a
 * fresh network fetch every single time, "Not connected to a server"
 * permanently once offline even for a track whose lyrics had already been
 * fetched moments earlier in the same session.
 *
 * A genuinely-no-lyrics result is cached too (`kind = "none"`), so an
 * instrumental track doesn't get re-fetched pointlessly every time it plays —
 * but a fetch that fails (network/API error) is never cached, so it's
 * retried next time rather than permanently remembered as "no lyrics."
 */
class LyricsRepository(
    private val apiHolder: ApiLookup,
    filesDir: File,
) {
    private val diskCacheDir = File(filesDir, "lyrics").apply { mkdirs() }

    suspend fun getLyrics(trackId: String): LyricsState {
        readFromDisk(trackId)?.let { return it.toState() }
        // A server known not to offer lyrics is not asked: it would only answer with an error.
        if (Capabilities.cannot(ServerScope.serverOf(trackId), Capability.LYRICS)) return LyricsState.NoLyrics

        val api = apiHolder.forId(trackId) ?: return LyricsState.Error("Not connected to a server")
        return try {
            val entries = api.getLyrics(trackId)
            // Prefer an explicit "main" entry if the server bothers to tag one
            // (spec allows translation/pronunciation alongside it); otherwise
            // take the first synced entry, then just the first entry with any
            // lines at all. Real probes against this project's Navidrome only
            // ever returned a single entry with no `kind` set, so this is
            // defensive rather than exercised.
            val best = entries.firstOrNull { it.kind == "main" && it.lines.isNotEmpty() }
                ?: entries.firstOrNull { it.synced && it.lines.isNotEmpty() }
                ?: entries.firstOrNull { it.lines.isNotEmpty() }
            val cached = when {
                best == null -> CachedLyrics(kind = "none")
                best.synced -> CachedLyrics(kind = "synced", lines = best.lines.map { CachedLine(it.startMs, it.text) })
                else -> CachedLyrics(kind = "plain", text = best.lines.joinToString("\n") { it.text }.trim())
            }
            writeToDisk(trackId, cached)
            cached.toState()
        } catch (e: Exception) {
            AppLogger.e("LyricsRepository", "getLyrics($trackId) failed", e)
            LyricsState.Error(e.message ?: "Couldn't load lyrics")
        }
    }

    private fun readFromDisk(trackId: String): CachedLyrics? {
        val file = diskCacheFile(trackId)
        if (!file.exists()) return null
        return try {
            Json.decodeFromString<CachedLyrics>(file.readText())
        } catch (e: Exception) {
            // A corrupt/unreadable cache entry shouldn't block re-fetching —
            // treat it the same as a cache miss.
            null
        }
    }

    private fun writeToDisk(trackId: String, cached: CachedLyrics) {
        // Best-effort: a disk-cache write failure shouldn't fail the result
        // that's already been resolved for this call.
        runCatching { diskCacheFile(trackId).writeText(Json.encodeToString(cached)) }
    }

    private fun diskCacheFile(trackId: String): File {
        return File(diskCacheDir, "${ServerScope.fileKey(trackId)}.json")
    }
}
