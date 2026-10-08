package com.musicplus.app.data

import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException

/**
 * Streams a response body straight to [destination] in fixed-size chunks, and makes sure that what ends up on disk is the whole file.
 * Written once for every backend: it was two copies (one per client) and they had diverged, only Subsonic's rejecting an XML error page.
 *
 * Why chunks: reading a track as one `ByteArray` briefly held all of it in memory and crashed the app with `OutOfMemoryError` on a real
 * ~30 MB track (Android's per-process heap growth limit is commonly ~128 MB, and one contiguous 30 MB allocation does not fit once
 * anything else is resident, confirmed on-device 2026-09-18); this keeps memory use roughly constant whatever the size.
 *
 * Everything below exists so that a file which is not the whole song never reaches disk looking like one. A cut-off body used to end the
 * read loop exactly as a finished one does (`readAvailable` returns -1 either way), so a fraction of a song was stored as complete: the
 * HTTP engine's default cap on a whole request cut transfers off without an error this code noticed (2026-09-19). So the status, the
 * content type, the channel's own failure and the announced length are all checked.
 *
 * [lease] is what the [FetchGate] gave this transfer: it is asked before each chunk (a transfer a more important one is outranking
 * pauses there) and told how many bytes moved.
 */
internal suspend fun HttpStatement.streamToFile(destination: File, lease: FetchGate.Lease? = null, what: String = "download") {
    execute { response ->
        val expectedBytes = response.contentLength()
        AppLogger.d(TAG, "$what: got response ${response.status}, contentLength=$expectedBytes")
        if (!response.status.isSuccess()) throw IOException("server answered ${response.status}")
        // A server can report an error as an ordinary 200 carrying a JSON, XML or text document, not as an audio file (Subsonic does).
        val type = response.contentType()
        if (type != null && (type.match(ContentType.Application.Json) || type.match(ContentType.Application.Xml) || type.match(ContentType.Text.Any))) {
            throw IOException("server answered with $type instead of audio")
        }
        val channel = response.bodyAsChannel()
        var totalBytes = 0L
        destination.outputStream().use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                lease?.checkpoint()
                val bytesRead = channel.readAvailable(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    lease?.bytes(bytesRead)
                }
            }
        }
        channel.closedCause?.let { throw IOException("connection dropped after $totalBytes bytes", it) }
        if (expectedBytes != null && totalBytes != expectedBytes) {
            throw IOException("cut short: got $totalBytes of $expectedBytes bytes")
        }
        AppLogger.d(TAG, "$what: wrote $totalBytes bytes")
    }
}

private const val TAG = "FileDownload"
private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
