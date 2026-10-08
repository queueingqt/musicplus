package com.musicplus.app.data

import android.security.NetworkSecurityPolicy

/**
 * Whether the SDK's player can fetch [url] itself, so a track can be handed to it as a
 * `UrlSource` and start playing while it downloads.
 *
 * media3 inside `LightAudioPlayer` goes through `HttpURLConnection`, which refuses plain `http://`
 * unless the app's manifest sets `usesCleartextTraffic` (the app's own Ktor/CIO client is not
 * subject to that, which is why API calls work over `http://` regardless). The `cleartext-http` capability in
 * lighttool.toml sets it; a build without that capability gets `false` for an `http://` server and
 * falls back to download-then-play. Asked of the platform
 * rather than assumed, so it stays right whichever way a given build was made.
 */
internal fun playerCanFetch(url: String): Boolean {
    if (url.startsWith("https://", ignoreCase = true)) return true
    val host = try {
        java.net.URI(url).host
    } catch (e: java.net.URISyntaxException) {
        null
    } ?: return false
    return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
}
