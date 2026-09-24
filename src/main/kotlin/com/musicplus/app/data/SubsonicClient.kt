package com.musicplus.app.data

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/** Everything needed to reach one Subsonic/Navidrome server. */
data class ServerConfig(
    val baseUrl: String, // e.g. "https://music.example.com" — no trailing slash, no /rest suffix
    val username: String,
    val password: String,
)

/**
 * One saved server connection (issue: multi-server support) — [ServerConfig] plus
 * a stable [id] and a person-facing [name] so more than one can be listed/picked
 * between. [ServerConfigRepository] persists a list of these; exactly one is
 * "active" at a time (the one [ServerConfigRepository.serverConfig] resolves to).
 *
 * [kind] defaults to [ServerKind.SUBSONIC] so every profile saved before Jellyfin support existed decodes unchanged.
 * [username]/[password] mean different things per [kind]: for Subsonic they're sent on every request (see
 * [SubsonicClient.authParams]); for Jellyfin they're used only to obtain [jellyfinAccessToken] in the first place
 * (see [authenticateJellyfin]) and kept afterward only so a future version could silently re-authenticate if that
 * token is ever revoked — every real Jellyfin request uses [jellyfinAccessToken] alone. [jellyfinUserId] is the
 * account's own id on that server, needed by nearly every Jellyfin endpoint (favorites, playlists, browsing are all
 * scoped to a signed-in user, unlike Subsonic). Both are null for a Subsonic profile.
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val kind: ServerKind = ServerKind.SUBSONIC,
    val jellyfinAccessToken: String? = null,
    val jellyfinUserId: String? = null,
) {
    fun toServerConfig() = ServerConfig(baseUrl = baseUrl, username = username, password = password)
}

class SubsonicApiException(val code: Int, message: String) : Exception(message)

/**
 * Thin Subsonic REST client (https://www.subsonic.org/pages/api.jsp). Works against
 * Navidrome and any other Subsonic-compatible server. No Light-provided HTTP
 * primitive exists (see SETUP.md's SDK reference notes), so this uses Ktor.
 *
 * Engine is CIO, not OkHttp: many self-hosted Subsonic servers run plain
 * `http://` on a LAN/tailnet, and OkHttp's Android platform integration enforces
 * Android's default cleartext-traffic block with no way to override it here (the
 * SDK's manifest generator forbids a custom AndroidManifest.xml, so there's no
 * `android:usesCleartextTraffic`/network-security-config to set). CIO is Ktor's
 * own pure-Kotlin engine and isn't subject to that Android-specific check.
 * Confirmed necessary via on-device testing against a real http:// server
 * (2026-09-17) — see project memory note.
 */
class SubsonicClient(
    private val config: ServerConfig,
    /** Told after every API request whether the server could be reached (true) or not (false) — see [ServerReachability]. */
    private val onReachable: (Boolean) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = newJsonHttpClient(json)

    // Normalized once here rather than trusted from the caller — ServerConfig.baseUrl
    // is only trimmed of a trailing slash on the save path (ServerConfigRepository),
    // not the raw value SettingsScreenViewModel.testConnection() builds straight from
    // the typed field. An untrimmed baseUrl produces a double slash before "rest/...",
    // which Navidrome's router doesn't match — it falls through to its web SPA at
    // "/app/" (200 OK, text/html) instead of 404ing, which is what actually surfaced
    // this on-device (a NoTransformationFoundException, not an obviously-URL-shaped
    // error). Confirmed 2026-09-17.
    private val baseUrl: String = config.baseUrl.trimEnd('/')

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_ID = "Music+"
        private val SALT_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        /** See [downloadToFile]'s doc — the fixed chunk size that keeps its memory use constant regardless of file size. */
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

        /**
         * Whole-call limit for an ordinary API request or a cover image — the
         * client itself has no total cap (see [newJsonHttpClient]), so anything
         * that is supposed to be quick says so here. Generous on purpose: a
         * 500-album page from a busy server can take well over the old 15 s.
         */
        private const val API_CALL_TIMEOUT_MS = 60_000L
    }

    private fun randomSalt(length: Int = 12): String =
        (1..length).map { SALT_CHARS[Random.nextInt(SALT_CHARS.size)] }.joinToString("")

    /**
     * Reused across calls rather than `MessageDigest.getInstance("MD5")` fresh
     * each time — confirmed live, 2026-09-18, via direct instrumentation: a
     * fresh `getInstance()` (JCA provider lookup) on every call was the
     * dominant cost behind [observeAllTracks]-class screens taking multiple
     * real seconds to load on-device (~9s for ~7000 tracks on a first cold
     * run), not database or Compose work — those measured at ~0ms in the
     * same instrumentation. `synchronized` because this client is shared
     * across concurrent coroutines (playback, downloads, art fetches, list
     * refreshes can all be building URLs at once); `MessageDigest.digest()`
     * itself resets the instance's internal state after every call, so
     * serialized reuse across callers is safe, just not *concurrent* use of
     * the one instance.
     */
    private val md5Digest = MessageDigest.getInstance("MD5")

    private fun md5Hex(input: String): String {
        val bytes = synchronized(md5Digest) { md5Digest.digest(input.toByteArray(Charsets.UTF_8)) }
        // Manual hex encoding, not `"%02x".format(it)` per byte — confirmed
        // live as the other real contributor to the same slowdown described
        // above: `String.format` re-parses its format string and goes
        // through locale-aware number formatting on every single call, 16
        // times per hash (once per byte).
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }
    }

    /** Auth + boilerplate query params every Subsonic call needs. */
    private fun authParams(): List<Pair<String, String>> {
        val salt = randomSalt()
        val token = md5Hex(config.password + salt)
        return listOf(
            "u" to config.username,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT_ID,
            "f" to "json",
        )
    }

    /** Builds a fully-authenticated URL for endpoints consumed directly (stream/download/cover art). */
    fun endpointUrl(method: String, params: List<Pair<String, String>> = emptyList()): String {
        val all = authParams() + params
        val query = all.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return "$baseUrl/rest/$method?$query"
    }

    /** Calls a JSON endpoint and unwraps the `subsonic-response` envelope. */
    suspend fun call(method: String, params: List<Pair<String, String>> = emptyList()): SubsonicResponse {
        val httpResponse = try {
            http.get("$baseUrl/rest/$method") {
                (authParams() + params).forEach { (k, v) -> parameter(k, v) }
                timeout { requestTimeoutMillis = API_CALL_TIMEOUT_MS }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        // A proxy in front of a server that is down answers for it with a 502-504.
        if (httpResponse.status.value in 502..504) {
            onReachable(false)
            throw IOException("server answered ${httpResponse.status}")
        }
        onReachable(true)
        val response: SubsonicEnvelope = httpResponse.body()
        val body = response.response
        if (!body.isOk) {
            val error = body.error
            throw SubsonicApiException(error?.code ?: -1, error?.message ?: "Subsonic request failed")
        }
        return body
    }

    /** Raw bytes from a small binary endpoint (getCoverArt.view — a few tens of KB) — same client/engine as [call], so it gets the same cleartext-over-CIO handling. Never use this for a full track (see [downloadToFile]'s doc). */
    suspend fun getBytes(method: String, params: List<Pair<String, String>> = emptyList()): ByteArray {
        AppLogger.d("SubsonicClient", "getBytes($method): issuing request")
        val response = try {
            http.get("$baseUrl/rest/$method") {
                (authParams() + params).forEach { (k, v) -> parameter(k, v) }
                timeout { requestTimeoutMillis = API_CALL_TIMEOUT_MS }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isUnreachable()) onReachable(false)
            throw e
        }
        onReachable(response.status.value !in 502..504)
        AppLogger.d("SubsonicClient", "getBytes($method): got response ${response.status}, contentLength=${response.contentLength()}")
        val bytes: ByteArray = response.body()
        AppLogger.d("SubsonicClient", "getBytes($method): read ${bytes.size} bytes")
        return bytes
    }

    /**
     * Streams a binary endpoint's response body straight to [destination] —
     * for `stream.view`/`download.view`, where the payload is a whole audio
     * file, not a small image. [getBytes] materializes the entire response as
     * one `ByteArray` before the caller can do anything with it; that crashed
     * with `OutOfMemoryError` on a real ~30MB track (Android's per-process
     * heap growth limit is commonly ~128MB, and one contiguous 30MB
     * allocation doesn't fit once anything else is already resident —
     * confirmed on-device, 2026-09-18). Both callers of the old
     * `streamBytes`/`downloadBytes` only ever did `file.writeBytes(...)`
     * immediately afterward anyway, so there was never a reason to hold the
     * whole file in memory at once — this reads and writes in fixed-size
     * chunks instead, keeping memory use roughly constant regardless of file
     * size.
     */
    suspend fun downloadToFile(
        method: String,
        destination: File,
        params: List<Pair<String, String>> = emptyList(),
        lease: FetchGate.Lease? = null,
    ) {
        AppLogger.d("SubsonicClient", "downloadToFile($method): issuing request")
        http.prepareGet("$baseUrl/rest/$method") {
            (authParams() + params).forEach { (k, v) -> parameter(k, v) }
        }.execute { response ->
            val expectedBytes = response.contentLength()
            AppLogger.d("SubsonicClient", "downloadToFile($method): got response ${response.status}, contentLength=$expectedBytes")
            // Everything below exists so that a file which is not the whole song
            // never reaches disk looking like one. A cut-off body used to end the
            // read loop exactly as a finished one does (readAvailable returns -1
            // either way), so a fraction of a song was stored as complete.
            if (!response.status.isSuccess()) throw IOException("server answered ${response.status}")
            // A Subsonic server reports an error as an ordinary 200 carrying a
            // JSON/XML document, not as an audio file.
            val type = response.contentType()
            if (type != null && (type.match(ContentType.Application.Json) || type.match(ContentType.Application.Xml) || type.match(ContentType.Text.Any))) {
                throw IOException("server answered with $type instead of audio")
            }
            val channel = response.bodyAsChannel()
            var totalBytes = 0L
            destination.outputStream().use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    // A transfer that a more important one is outranking pauses here — see FetchGate.
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
            AppLogger.d("SubsonicClient", "downloadToFile($method): wrote $totalBytes bytes")
        }
    }

    /**
     * `ping.view` — verifies the server is reachable and the credentials are valid.
     * Returns the underlying exception on failure (not just a boolean) — a silently
     * swallowed exception here made an earlier real bug (cleartext HTTP blocked by
     * Android's default network security policy) look identical to a wrong
     * password or an unreachable host, with no way to tell them apart.
     */
    suspend fun ping(): Result<Unit> = try {
        call("ping.view") // throws SubsonicApiException if the server itself reports failure
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * A login check that cannot be fooled. [ping] answers OK to anyone on some servers (Bandcamp's does, with no
     * credentials at all), so this asks for something only a signed-in person gets: one album. A server that refuses
     * the login says so as a Subsonic error (Navidrome: code 40) or, for Bandcamp, with a bare HTTP 500 and no body, which
     * arrives here as an answer that is not Subsonic at all. From a server that could be reached, both mean the
     * login was rejected. An unreachable server is still reported as what it is.
     */
    suspend fun checkLogin(): Result<Unit> = try {
        call("getAlbumList2.view", listOf("type" to "newest", "size" to "1"))
        Result.success(Unit)
    } catch (e: SubsonicApiException) {
        Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(if (e.isUnreachable()) e else SubsonicApiException(-1, "The server did not accept the login"))
    }

    /**
     * One harmless request that shows whether the server offers a feature (see [CapabilityRegistry]). Only meant for a
     * server that has just answered an ordinary question: an answer that is not Subsonic at all then means this
     * one endpoint is missing. Anything the server understood and refused ("not found", "missing parameter") counts as
     * having the feature; "not authorised" means this account cannot use it.
     */
    suspend fun probe(method: String, params: List<Pair<String, String>> = emptyList()): Support = try {
        call(method, params)
        Support.YES
    } catch (e: SubsonicApiException) {
        when (e.code) {
            NOT_FOUND, MISSING_PARAMETER -> Support.YES
            NOT_AUTHORIZED -> Support.NO
            else -> Support.UNKNOWN
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isUnreachable()) Support.UNKNOWN else Support.NO
    }
}

/** What a probe found out. UNKNOWN means it could not tell (the server was unreachable, or answered oddly), so nothing is concluded. */
enum class Support { YES, NO, UNKNOWN }

private const val MISSING_PARAMETER = 10
private const val NOT_AUTHORIZED = 50
private const val NOT_FOUND = 70

/** True when a request failed because the server could not be reached (or took too long), as opposed to it answering with something we did not like. */
internal fun Throwable.isUnreachable(): Boolean =
    this is IOException ||
        this is java.nio.channels.UnresolvedAddressException ||
        toString().contains("Timeout")
