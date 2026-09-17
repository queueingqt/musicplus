package com.lightwave.app

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
import com.lightwave.app.data.AppGraph
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Cover art for a track/album/artist, with a placeholder for every non-art state:
 * no [url] (nothing to show), the "Show album artwork" setting is off, or the
 * fetch/decode failed. [AlbumArtRepository] does the actual fetch+decode+cache;
 * this composable's job is just to ask it for a bitmap and render whatever comes
 * back (or the placeholder) at [size].
 *
 * The setting is checked here, in the same `LaunchedEffect` that triggers the
 * fetch — not just in what gets rendered — so turning it off actually stops the
 * background fetch/decode work per the issue's amendment, rather than just hiding
 * an image that's still being fetched underneath.
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
    val showArtwork by graph.appSettingsRepository.showAlbumArtwork.collectAsState(initial = true)

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(url, showArtwork) {
        bitmap = null
        loadFailed = false
        if (url != null && showArtwork) {
            val result = graph.albumArtRepository.getBitmap(url)
            bitmap = result
            loadFailed = result == null
        }
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
