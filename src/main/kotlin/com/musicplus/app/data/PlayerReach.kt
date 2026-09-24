package com.musicplus.app.data

import android.security.NetworkSecurityPolicy

/**
 * Whether the SDK's player can fetch [baseUrl] itself, so a track can be handed to it as a
 * `UrlSource` and start playing while it downloads.
 *
 * media3 inside `LightAudioPlayer` goes through `HttpURLConnection`, which refuses plain `http://`
 * unless the app's manifest sets `usesCleartextTraffic` (the app's own Ktor/CIO client is not
 * subject to that, which is why API calls work over `http://` regardless). The SDK's manifest
 * generator has no field for it, so `src/debug` and `src/release` carry an overlay that sets it for
 * the APKs built here; a build without the overlay (the SDK's own builder only reads `src/main`)
 * gets `false` for an `http://` server and falls back to download-then-play. Asked of the platform
 * rather than assumed, so it stays right whichever way a given build was made.
 */
internal fun playerCanFetch(baseUrl: String): Boolean {
    if (baseUrl.startsWith("https://", ignoreCase = true)) return true
    val host = try {
        java.net.URI(baseUrl).host
    } catch (e: java.net.URISyntaxException) {
        null
    } ?: return false
    return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
}
