package com.musicplus.app.data

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Everything needed to reach one already-authenticated Jellyfin server — see [authenticateJellyfin] for how [accessToken]/[userId] are obtained in the first place. */
data class JellyfinConfig(
    val baseUrl: String, // e.g. "http://192.168.1.10:8096" — no trailing slash
    val accessToken: String,
    val userId: String,
)

class JellyfinApiException(val code: Int, message: String) : Exception(message)

/**
 * Thin Jellyfin REST client (https://api.jellyfin.org). Structurally the Jellyfin-side twin of [SubsonicClient]: one
 * class that owns the raw request/auth machinery, kept separate from [JellyfinApi]'s endpoint-level, scoped-id calls.
 *
 * Unlike Subsonic's per-request salt+token scheme, Jellyfin issues one [JellyfinConfig.accessToken] up front (see
 * [authenticateJellyfin]) and every call after that just presents it — this class never sees a username or password.
 * A token that's been revoked (removed from the server's own Dashboard -> Devices) makes every call here fail; there
 * is deliberately no silent re-authentication in this version — [ServerConfigRepository] keeps the account's
 * username/password (encrypted) precisely so a future pass can add that without a storage-shape change, but for now
 * the recovery path is the same as fixing any other rejected login: re-save the server in Edit Server.
 *
 * Same CIO engine choice as [SubsonicClient], for the same reason — a self-hosted Jellyfin on a LAN/tailnet is just
 * as likely to be plain `http://` as a self-hosted Subsonic server, and CIO isn't subject to Android's
 * cleartext-traffic block the way OkHttp/HttpURLConnection are.
 */
class JellyfinClient(
    private val config: JellyfinConfig,
    /** Told after every request whether the server could be reached — see [ServerReachability]. */
    private val onReachable: (Boolean) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = newJsonHttpClient(json)
    private val baseUrl: String = config.baseUrl.trimEnd('/')

    val userId: String get() = config.userId
    val baseUrlIsHttps: Boolean = baseUrl.startsWith("https://", ignoreCase = true)

    companion object {
        private const val API_CALL_TIMEOUT_MS = 60_000L
    }

    private fun tokenHeader() = "MediaBrowser Token=\"${config.accessToken}\""

    /** Builds a fully-authenticated URL for endpoints consumed directly (stream/download/image) — the query-param equivalent of [tokenHeader], confirmed working against a real server (2026-09-22): Jellyfin accepts `api_key` on any endpoint, not just ones meant for browser `<img>` tags. */
    fun endpointUrl(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val all = params + ("api_key" to config.accessToken)
        val query = all.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return "$baseUrl$path?$query"
    }

    /** GET returning a decoded JSON body. [path] starts with `/`. */
    suspend fun <T> get(path: String, params: List<Pair<String, String>> = emptyList(), deserialize: suspend (io.ktor.client.statement.HttpResponse) -> T): T {
        val response = try {
            http.get("$baseUrl$path") {
                header("Authorization", tokenHeader())
                params.forEach { (k, v) -> parameter(k, v) }
                timeout { requestTimeoutMillis = API_CALL_TIMEOUT_MS }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        return respond(response, deserialize)
    }

    private suspend fun <T> respond(response: io.ktor.client.statement.HttpResponse, deserialize: suspend (io.ktor.client.statement.HttpResponse) -> T): T {
        if (response.status.value in 502..504) {
            onReachable(false)
            throw IOException("server answered ${response.status}")
        }
        onReachable(true)
        if (!response.status.isSuccess()) {
            throw JellyfinApiException(response.status.value, "Jellyfin request failed: ${response.status}")
        }
        return deserialize(response)
    }

    /** POST with query params and an optional JSON body (Jellyfin's write endpoints mix both — see each [JellyfinApi] call for which). An empty object body is sent when [body] is null and [forceJsonBody]: some endpoints 415 without a `Content-Type: application/json` body at all, even an empty one. */
    suspend fun <T> post(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
        forceJsonBody: Boolean = false,
        deserialize: suspend (io.ktor.client.statement.HttpResponse) -> T,
    ): T {
        val response = try {
            http.post("$baseUrl$path") {
                header("Authorization", tokenHeader())
                params.forEach { (k, v) -> parameter(k, v) }
                timeout { requestTimeoutMillis = API_CALL_TIMEOUT_MS }
                contentType(ContentType.Application.Json)
                when {
                    body != null -> setBody(body)
                    forceJsonBody -> setBody("{}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        return respond(response, deserialize)
    }

    suspend fun delete(path: String, params: List<Pair<String, String>> = emptyList()) {
        val response = try {
            http.delete("$baseUrl$path") {
                header("Authorization", tokenHeader())
                params.forEach { (k, v) -> parameter(k, v) }
                timeout { requestTimeoutMillis = API_CALL_TIMEOUT_MS }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        respond(response) {}
    }

    /** Raw bytes from a small binary endpoint (cover art) — same client/engine as everything else, so it gets the same cleartext handling. Never use this for a full track — see [downloadToFile]. */
    suspend fun getBytes(path: String, params: List<Pair<String, String>> = emptyList()): ByteArray {
        val response = try {
            http.get("$baseUrl$path") {
                header("Authorization", tokenHeader())
                params.forEach { (k, v) -> parameter(k, v) }
                timeout { requestTimeoutMillis = API_CALL_TIMEOUT_MS }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        onReachable(response.status.value !in 502..504)
        if (!response.status.isSuccess()) throw JellyfinApiException(response.status.value, "Jellyfin request failed: ${response.status}")
        return response.body()
    }

    /** Streams a binary endpoint's response body straight to [destination] in fixed-size chunks — same reasoning as [SubsonicClient.downloadToFile] (a whole track held in memory as one ByteArray crashed the app on a real file, confirmed there; not re-proven here, same fix applied up front). */
    suspend fun downloadToFile(path: String, destination: File, params: List<Pair<String, String>> = emptyList(), lease: FetchGate.Lease? = null) {
        http.prepareGet("$baseUrl$path") {
            header("Authorization", tokenHeader())
            params.forEach { (k, v) -> parameter(k, v) }
        }.execute { response ->
            val expectedBytes = response.contentLength()
            if (!response.status.isSuccess()) throw IOException("server answered ${response.status}")
            val type = response.contentType()
            if (type != null && (type.match(ContentType.Application.Json) || type.match(ContentType.Text.Any))) {
                throw IOException("server answered with $type instead of audio")
            }
            val channel = response.bodyAsChannel()
            var totalBytes = 0L
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
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
        }
    }

    /** A cheap authenticated request that says whether the server (and this token) is still good — Jellyfin has no dedicated "ping", so this reuses the same one-item lookup [checkLogin] does. */
    suspend fun ping(): Result<Unit> = checkLogin()

    /** Confirms the token is still valid against a real signed-in question, not just that the server is up — `GET /Users/{userId}` is rejected with 401 for a revoked/expired token, and 200 for anything else reachable. */
    suspend fun checkLogin(): Result<Unit> = try {
        get<Unit>("/Users/${config.userId}") { it.body<JellyfinUser>(); Unit }
        Result.success(Unit)
    } catch (e: JellyfinApiException) {
        Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(if (e.isUnreachable()) e else JellyfinApiException(-1, "The server did not accept the login"))
    }
}

/**
 * The one place a Jellyfin username/password is ever used — everything else in this app only ever sees the resulting
 * [JellyfinConfig.accessToken]. Called when a Jellyfin server is added or its login is changed (see
 * [ServerConfigRepository]) and by [ServerEditScreen]'s "test connection". [deviceId] should be the same stable,
 * per-install id every time (see [JellyfinDeviceId]) — a fresh one on every call would register a new "device" in
 * the server's own Dashboard -> Devices on every single login.
 */
suspend fun authenticateJellyfin(baseUrl: String, username: String, password: String, deviceId: String, appVersion: String): Result<JellyfinAuthResult> {
    val client = newJsonHttpClient(Json { ignoreUnknownKeys = true })
    val trimmedUrl = baseUrl.trimEnd('/')
    return try {
        val response = client.post("$trimmedUrl/Users/AuthenticateByName") {
            header("Authorization", "MediaBrowser Client=\"Music+\", Device=\"Light Phone III\", DeviceId=\"$deviceId\", Version=\"$appVersion\"")
            contentType(ContentType.Application.Json)
            setBody(mapOf("Username" to username, "Pw" to password))
            timeout { requestTimeoutMillis = 30_000L }
        }
        if (!response.status.isSuccess()) {
            return Result.failure(JellyfinApiException(response.status.value, "The server did not accept the login"))
        }
        val result: JellyfinAuthResult = response.body()
        if (result.AccessToken == null || result.User == null) {
            Result.failure(JellyfinApiException(-1, "The server did not accept the login"))
        } else {
            Result.success(result)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(if (e.isUnreachable()) e else JellyfinApiException(-1, "The server did not accept the login"))
    }
}
