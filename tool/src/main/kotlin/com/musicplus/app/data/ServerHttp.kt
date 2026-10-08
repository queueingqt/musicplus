package com.musicplus.app.data

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.IOException

/**
 * What both backends' clients do around every request, in one place: send it, and keep the server's reachability up to date. A failure to
 * connect (or a timeout) marks the server down and is rethrown; a 502-504 (a proxy in front of a server that is down answers for it) marks it
 * down and is thrown as an [IOException]; any other answer, whatever its status, marks it up and is handed back for the caller to read. A
 * cancelled request is just cancelled.
 *
 * The engine is a constructor argument so a test can point a client at a server of its own, and the client is [Closeable] so one that is no
 * longer wanted (a server removed or edited, a one-off login check) lets go of its connections.
 */
class ServerHttp(
    val http: HttpClient,
    /** Told after every request whether the server could be reached (true) or not (false): see [ServerReachability]. */
    private val onReachable: (Boolean) -> Unit,
) : Closeable {
    suspend fun send(request: suspend HttpClient.() -> HttpResponse): HttpResponse {
        val response = try {
            http.request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        if (response.status.value in 502..504) {
            onReachable(false)
            throw ServerDownException("server answered ${response.status}")
        }
        onReachable(true)
        return response
    }

    override fun close() = http.close()
}

/** A proxy in front of a server that is down answered for it (502-504). The server is out, which is not the same as a link that is overloaded. */
class ServerDownException(message: String) : IOException(message)
