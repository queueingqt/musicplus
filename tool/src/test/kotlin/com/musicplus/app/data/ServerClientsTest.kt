package com.musicplus.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The request layer under both backends, against a server of our own: the same scenarios run on the Subsonic client and the Jellyfin client,
 * so what a request does to reachability, and what a bad answer becomes, is the same whichever one asks.
 */
class ServerClientsTest {
    private class Reply(val status: Int, val type: String, val body: ByteArray)

    private val picture = ByteArray(40_000) { (it % 251).toByte() }

    private val replies = mapOf(
        "ok" to Reply(200, "image/jpeg", picture),
        "missing" to Reply(404, "text/plain", ByteArray(0)),
        "down" to Reply(502, "text/plain", ByteArray(0)),
        "json" to Reply(200, "application/json", """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}""".toByteArray()),
    )

    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor = thread(isDaemon = true) {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (e: IOException) { return@thread }
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket): Unit = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = input.readLine()?.split(" ")?.getOrNull(1)?.substringBefore('?') ?: return
        while (input.readLine().let { line -> line != null && line.isNotEmpty() }) { /* the rest of the request headers */ }
        val reply = replies[path.substringAfterLast('/').removeSuffix(".view")] ?: Reply(404, "text/plain", ByteArray(0))
        val out = it.getOutputStream()
        out.write("HTTP/1.1 ${reply.status} X\r\nContent-Type: ${reply.type}\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(reply.body)
        out.flush()
    }

    private val base = "http://127.0.0.1:${listener.localPort}"
    private val reachability = CopyOnWriteArrayList<Boolean>()
    private val clients = mutableListOf<java.io.Closeable>()

    /** A way to ask each backend for a small binary thing by scenario name. */
    private class Backend(val name: String, val fetch: suspend (scenario: String) -> ByteArray)

    private fun backends(baseUrl: String = base): List<Backend> {
        val subsonic = SubsonicClient(ServerConfig(baseUrl, "u", "p"), { reachability += it }).also { clients += it }
        val jellyfin = JellyfinClient(JellyfinConfig(baseUrl, "token", "user"), { reachability += it }).also { clients += it }
        return listOf(
            Backend("subsonic") { subsonic.getBytes("$it.view") },
            Backend("jellyfin") { jellyfin.getBytes("/$it") },
        )
    }

    @AfterTest
    fun cleanUp() {
        listener.close()
        clients.forEach { it.close() }
    }

    @Test
    fun aPictureComesBackByteForByteAndMarksTheServerReachable() = runBlocking<Unit> {
        for (backend in backends()) {
            reachability.clear()
            assertContentEquals(picture, backend.fetch("ok"), backend.name)
            assertEquals(listOf(true), reachability.toList(), backend.name)
        }
    }

    @Test
    fun aNotFoundIsAFailureNotAPicture() = runBlocking<Unit> {
        for (backend in backends()) {
            assertFailsWith<Exception>(backend.name) { backend.fetch("missing") }
        }
    }

    @Test
    fun aProxyAnsweringForADownServerMarksItUnreachableAndFailsTheSameWayForBoth() = runBlocking<Unit> {
        for (backend in backends()) {
            reachability.clear()
            val e = assertFailsWith<IOException>(backend.name) { backend.fetch("down") }
            assertTrue("502" in e.message.orEmpty(), "${backend.name}: ${e.message}")
            assertEquals(listOf(false), reachability.toList(), backend.name)
        }
    }

    @Test
    fun aServerThatCannotBeReachedMarksItUnreachable() = runBlocking<Unit> {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        for (backend in backends("http://127.0.0.1:$closedPort")) {
            reachability.clear()
            assertFailsWith<Exception>(backend.name) { backend.fetch("ok") }
            assertEquals(listOf(false), reachability.toList(), backend.name)
        }
    }

    @Test
    fun aSubsonicErrorServedAsOkIsNotAPicture() = runBlocking<Unit> {
        // Subsonic reports a failure as a 200 carrying its error document; the client refuses it instead of handing it on as art.
        val subsonic = backends().first { it.name == "subsonic" }
        assertFailsWith<SubsonicApiException> { subsonic.fetch("json") }
    }

    @Test
    fun aClientThatIsClosedLetsGoOfItsConnections() = runBlocking<Unit> {
        val client = SubsonicClient(ServerConfig(base, "u", "p"))
        client.close()
        assertFailsWith<Exception> { client.getBytes("ok.view") }
    }
}
