package com.musicplus.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** A host that can't be reached should fail promptly rather than hang. */
private const val CONNECT_TIMEOUT_MS = 15_000L

/**
 * The longest a connection may sit with no bytes moving. An idle limit, not a
 * cap on the transfer: a big file on a slow link is fine as long as it keeps
 * arriving.
 */
private const val IDLE_TIMEOUT_MS = 60_000L

/**
 * Shared Ktor client construction. [SubsonicClient]'s long-lived instance and
 * [VersionCheckRepository]'s one-shot GitHub release check each independently
 * built this exact `HttpClient(CIO) { install(ContentNegotiation) { json(...) } }`
 * shape — confirmed live, 2026-09-18 architecture review. (`CrashReporter`'s own
 * HTTP calls are deliberately different — plain [java.net.HttpURLConnection],
 * not Ktor, since it must survive process teardown — and stay separate.)
 *
 * Engine is CIO, not OkHttp — see [SubsonicClient]'s own doc for why (Android's
 * cleartext-traffic block, no way to override it under this SDK).
 *
 * **No cap on the whole request.** The CIO engine's own default is 15 seconds
 * for an entire request, response body included. This client also carries whole
 * audio files (`stream.view`, `download.view`, 4-40 MB each), and on a link
 * that delivers a few hundred KB/s a song simply takes longer than that. What
 * the cap did was cut the transfer off mid-file *without an error the download
 * code noticed*, so a fraction of the song was stored as if it were the whole
 * thing — songs that showed as downloaded but went silent partway through, and
 * (when the cut landed in a queue-building coroutine) crashed the app. Ordinary
 * API calls that should be quick set their own limit per request instead — see
 * [SubsonicClient.call].
 */
fun newJsonHttpClient(json: Json = Json { ignoreUnknownKeys = true }): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        engine { requestTimeout = 0 } // 0 = no total cap; see the doc above
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = IDLE_TIMEOUT_MS
        }
    }
