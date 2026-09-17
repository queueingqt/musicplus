package com.lightwave.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController

/**
 * `LightActivity.setContent {}` renders each screen's `Content()` directly — it does
 * NOT wrap it in `LightTheme` (confirmed by reading the real source; every SDK
 * preview wraps itself explicitly for exactly this reason). Without it,
 * `LightText`/`LightIcon` still look right by accident (they read
 * `LightThemeTokens.colors`, whose `CompositionLocalOf` default happens to equal
 * `LightThemeColors.Dark`), but anything using Material3 directly — like the SDK's
 * own `LightTextInputEditor`, which wraps itself in a bare `Surface {}` — falls back
 * to Material3's own default light color scheme instead: a white screen with no
 * visible way to tell what's wrong (found via on-device testing, not compiling).
 *
 * Every screen's `Content()` must wrap its root composable in this.
 */
@Composable
fun LightwaveTheme(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors, content = content)
}
