package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.musicplus.app.data.NewerVersion
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class VersionAvailableScreenViewModel : LightViewModel<Unit>()

/**
 * Text-only — no tappable link, no in-app updater. LightOS tools can't open a
 * browser or trigger a package install from code (see
 * VersionCheckRepository's doc for the confirmed SDK-level reasons why), so
 * this just surfaces the version and URL as plain text, wrapped in a
 * [SelectionContainer] so it's at least copyable (long-press to select, same
 * as any selectable Android text) for anyone who wants to check it from
 * another device.
 */
class VersionAvailableScreen(
    activity: SealedLightActivity,
    private val newerVersion: NewerVersion,
) : LightScreen<Unit, VersionAvailableScreenViewModel>(activity) {

    override val viewModelClass = VersionAvailableScreenViewModel::class.java
    override fun createViewModel() = VersionAvailableScreenViewModel()

    @Composable
    override fun Content() {
        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("New Version Available"),
                )
            },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                LightText(
                    text = "Version ${newerVersion.versionName} is available — you're on ${BuildConfig.VERSION_NAME}.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Get it from a computer:",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                SelectionContainer {
                    LightText(
                        text = newerVersion.releaseUrl,
                        variant = LightTextVariant.Copy,
                        monospace = true,
                    )
                }
            }
        }
    }
}
