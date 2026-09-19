package com.musicplus.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.musicplus.app.data.AppDisplayPrefs
import com.musicplus.app.data.AppGraph
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Cover art for a track/album/artist, with a placeholder for a genuine no-art
 * state: no [url], or the fetch/decode failed. [AlbumArtRepository] does the
 * actual fetch+decode+cache; this composable's job is just to ask it for a
 * bitmap and render whatever comes back (or the placeholder) at [size].
 *
 * When the "Show album artwork" setting is off, this renders nothing at all —
 * zero size, not a placeholder box — so screens collapse to text-only instead
 * of keeping a grid of empty/broken-looking image slots. Reported live: an
 * empty box with a generic icon still read as "the images failed to load",
 * not "artwork is intentionally off". The setting is checked before any other
 * state (including the fetch itself) so turning it off also actually stops
 * the background fetch/decode work, not just hides an in-flight image.
 */
@Composable
fun AlbumArt(
    lightContext: SealedLightContext,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    placeholderIconSize: Float = 2f,
) {
    val graph = remember(lightContext) { AppGraph.from(lightContext) }
    // AppDisplayPrefs.showAlbumArtwork, not appSettingsRepository.showAlbumArtwork
    // directly — see AppDisplayPrefs's doc for why (a raw collectAsState(initial)
    // on the cold DataStore Flow flashed real art every time this composable is
    // freshly composed, which is constantly).
    val showArtwork by AppDisplayPrefs.showAlbumArtwork.value.collectAsState()

    if (!showArtwork) return

    // Seeded from a synchronous cache peek so a row that's already been loaded once
    // (e.g. scrolled out of view and back) shows its art on the very first frame
    // instead of flashing back to the placeholder while getBitmap() re-confirms a
    // cache hit it's going to return instantly anyway.
    var bitmap by remember(url) { mutableStateOf(url?.let { graph.albumArtRepository.peekCached(it) }) }
    var loadFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url == null) {
            bitmap = null
            loadFailed = false
            return@LaunchedEffect
        }
        val cached = graph.albumArtRepository.peekCached(url)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        bitmap = null
        loadFailed = false
        val result = graph.albumArtRepository.getBitmap(url)
        bitmap = result
        loadFailed = result == null
    }

    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .size(size)
            .background(colors.contentSecondary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Covers the null-url, setting-off, still-loading, and failed states alike —
            // deliberately one placeholder, not four different ones, since none of them
            // are actionable by the person looking at it.
            LightIcon(
                icon = LightIcons.MEDIA,
                size = placeholderIconSize,
                contentDescription = if (loadFailed) "Artwork unavailable" else "No artwork",
            )
        }
    }
}
