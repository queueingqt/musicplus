package com.lightwave.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
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
 * Navidrome and any other Subsonic-compatible server. Ktor + the OkHttp engine is
 * the pattern the SDK's own `tool` and `examples/weather` use for network access —
 * no Light-provided HTTP primitive exists (see SETUP.md's SDK reference notes).
 */
class SubsonicClient(private val config: ServerConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

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
        return "${config.baseUrl}/rest/$method?$query"
    }

    /** Calls a JSON endpoint and unwraps the `subsonic-response` envelope. */
    suspend fun call(method: String, params: List<Pair<String, String>> = emptyList()): SubsonicResponse {
        val response: SubsonicEnvelope = http.get("${config.baseUrl}/rest/$method") {
            (authParams() + params).forEach { (k, v) -> parameter(k, v) }
        }.body()
        val body = response.response
        if (!body.isOk) {
            val error = body.error
            throw SubsonicApiException(error?.code ?: -1, error?.message ?: "Subsonic request failed")
        }
        return body
    }

    /** `ping.view` — verifies the server is reachable and the credentials are valid. */
    suspend fun ping(): Boolean = try {
        call("ping.view").isOk
    } catch (e: Exception) {
        false
    }
}
