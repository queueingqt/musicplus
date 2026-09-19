package com.musicplus.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

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
 */
fun newJsonHttpClient(json: Json = Json { ignoreUnknownKeys = true }): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
    }
