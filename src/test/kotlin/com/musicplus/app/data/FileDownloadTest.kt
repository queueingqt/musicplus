package com.musicplus.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one shared download, against a real local HTTP server: what ends up on disk is the whole file, or the call fails. Each case is a
 * way a file that is not the whole song used to be stored as one.
 */
class FileDownloadTest {
    private val dir: File = Files.createTempDirectory("filedownload-test").toFile()
    private val client: HttpClient = newJsonHttpClient()
    private val audio = ByteArray(300_000) { (it % 251).toByte() }

    /** One response per path; the connection is closed after each, so a body that is shorter than announced simply ends. */
    private class Reply(val status: Int, val type: String, val body: ByteArray, val announced: Long?, val sendBytes: Int = body.size)

    private val replies = mapOf(
        "/ok" to Reply(200, "audio/mpeg", audio, audio.size.toLong()),
        "/chunked" to Reply(200, "audio/mpeg", audio, announced = null),
        "/json" to Reply(200, "application/json", """{"subsonic-response":{"status":"failed"}}""".toByteArray(), null),
        "/xml" to Reply(200, "application/xml", "<subsonic-response status=\"failed\"/>".toByteArray(), null),
        "/text" to Reply(200, "text/plain", "an error page".toByteArray(), null),
        "/missing" to Reply(404, "audio/mpeg", ByteArray(0), 0),
        // Announces the whole file and stops partway: what a request cap, a dropped connection or a full disk on the server does.
        "/cut" to Reply(200, "audio/mpeg", audio, audio.size.toLong(), sendBytes = 100_000),
    )

    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor = thread(isDaemon = true) {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (e: IOException) { return@thread }
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = input.readLine()?.split(" ")?.getOrNull(1) ?: return
        while (input.readLine().let { line -> line != null && line.isNotEmpty() }) { /* the rest of the request headers */ }
        val reply = replies[path] ?: Reply(404, "text/plain", ByteArray(0), 0)
        val out = it.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 ${reply.status} X\r\nContent-Type: ${reply.type}\r\nConnection: close\r\n")
            if (reply.announced != null) append("Content-Length: ${reply.announced}\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.write(reply.body, 0, reply.sendBytes)
        out.flush()
    }

    private fun url(path: String) = "http://127.0.0.1:${listener.localPort}$path"

    @AfterTest
    fun cleanUp() {
        listener.close()
        client.close()
        dir.deleteRecursively()
    }

    private suspend fun download(path: String): File {
        val file = File(dir, "out.part")
        client.prepareGet(url(path)).streamToFile(file, null, "test $path")
        return file
    }

    @Test
    fun aWholeFileIsWrittenByteForByte() = runBlocking<Unit> {
        assertContentEquals(audio, download("/ok").readBytes())
    }

    @Test
    fun aBodyWithNoAnnouncedLengthIsAcceptedWhenItEndsCleanly() = runBlocking<Unit> {
        assertContentEquals(audio, download("/chunked").readBytes())
    }

    /** #55-era bug: the loop ended on EOF exactly as a finished file does, so a fraction of a song was stored as complete. */
    @Test
    fun aBodyCutShortIsAnErrorNotAFile() = runBlocking<Unit> {
        val e = assertFailsWith<IOException> { download("/cut") }
        assertTrue("cut short" in e.message.orEmpty() || "dropped" in e.message.orEmpty(), "says what happened: ${e.message}")
    }

    @Test
    fun aJsonOrTextOrXmlErrorServedAsOkIsNotAudio() = runBlocking<Unit> {
        for (path in listOf("/json", "/text", "/xml")) {
            val e = assertFailsWith<IOException>(path) { download(path) }
            assertTrue("instead of audio" in e.message.orEmpty(), "$path: ${e.message}")
        }
    }

    @Test
    fun aNonSuccessStatusIsAnError() = runBlocking<Unit> {
        val e = assertFailsWith<IOException> { download("/missing") }
        assertTrue("404" in e.message.orEmpty())
    }
}
