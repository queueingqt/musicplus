package com.lightwave.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.random.Random

/** Everything needed to reach one Subsonic/Navidrome server. */
data class ServerConfig(
    val baseUrl: String, // e.g. "https://music.example.com" — no trailing slash, no /rest suffix
    val username: String,
    val password: String,
)

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
 * (2026-09-17) — see project_lightwave memory note.
 */
class SubsonicClient(private val config: ServerConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
    }

    // Normalized once here rather than trusted from the caller — ServerConfig.baseUrl
    // is only trimmed of a trailing slash on the save path (ServerConfigRepository),
    // not the raw value SettingsScreenViewModel.testConnection() builds straight from
    // the typed field. An untrimmed baseUrl produces a double slash before "rest/...",
    // which Navidrome's router doesn't match — it falls through to its web SPA at
    // "/app/" (200 OK, text/html) instead of 404ing, which is what actually surfaced
    // this on-device (a NoTransformationFoundException, not an obviously-URL-shaped
    // error). Confirmed 2026-09-17.
    private val baseUrl: String = config.baseUrl.trimEnd('/')

    /** Used by PlaybackRepository to decide streaming vs. download-then-play — see its toAudioItem. */
    val baseUrlIsHttps: Boolean = baseUrl.startsWith("https://", ignoreCase = true)

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_ID = "Lightwave"
        private val SALT_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    }

    private fun randomSalt(length: Int = 12): String =
        (1..length).map { SALT_CHARS[Random.nextInt(SALT_CHARS.size)] }.joinToString("")

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
        val response: SubsonicEnvelope = http.get("$baseUrl/rest/$method") {
            (authParams() + params).forEach { (k, v) -> parameter(k, v) }
        }.body()
        val body = response.response
        if (!body.isOk) {
            val error = body.error
            throw SubsonicApiException(error?.code ?: -1, error?.message ?: "Subsonic request failed")
        }
        return body
    }

    /** Raw bytes from a binary endpoint (download.view, getCoverArt.view, ...) — same client/engine as [call], so it gets the same cleartext-over-CIO handling. */
    suspend fun getBytes(method: String, params: List<Pair<String, String>> = emptyList()): ByteArray =
        http.get("$baseUrl/rest/$method") {
            (authParams() + params).forEach { (k, v) -> parameter(k, v) }
        }.body()

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
}
