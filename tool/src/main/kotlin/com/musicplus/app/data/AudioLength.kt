package com.musicplus.app.data

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException

/**
 * How long a finished audio file plays, read from the file itself, or null when it cannot be read. A seam so tests need no Android.
 */
fun interface AudioLength {
    fun millis(file: File): Long?

    companion object {
        /** The phone's own media reader. A file it cannot open or find a duration in is "unknown", never "wrong". */
        val device = AudioLength { file ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }
}

/**
 * Tells a transcode that stopped early from a finished one. A transcode has no length announced in advance, so a server that ends the
 * body cleanly part-way leaves a file the size checks pass; its playing time against the song's is what gives it away.
 *
 * Deliberately loose: an mp3 with no length header gets an estimated duration, and a good file must never be thrown away. Only a file
 * both under [MIN_FRACTION] of the song and more than [MIN_GAP_MS] short counts, and an unreadable or unknown length never does.
 */
object CutShort {
    const val MIN_FRACTION = 0.9
    const val MIN_GAP_MS = 5_000L

    fun isCutShort(actualMs: Long?, expectedSec: Int): Boolean {
        if (actualMs == null || expectedSec <= 0) return false
        val expectedMs = expectedSec * 1000L
        return actualMs < expectedMs * MIN_FRACTION && expectedMs - actualMs > MIN_GAP_MS
    }

    /** Throws when [file] is clearly shorter than the song's [expectedSec]; the caller's failure path discards the file. */
    fun check(file: File, expectedSec: Int?, length: AudioLength) {
        if (expectedSec == null) return
        val actual = length.millis(file)
        AppLogger.d("CutShort", "${file.name}: plays ${actual}ms, song is ${expectedSec}s")
        if (isCutShort(actual, expectedSec)) throw IOException("cut short: plays ${actual}ms of ${expectedSec}s")
    }
}
