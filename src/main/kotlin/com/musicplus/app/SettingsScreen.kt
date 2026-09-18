package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.thelightphone.sdk.ui.lightClickable

// No real state — this screen is just the two menu rows below, each opening its
// own screen with its own ViewModel. LightScreen still requires one.
class SettingsScreenViewModel : LightViewModel<Unit>()

/**
 * Top-level menu of categories — mirrors the LightOS system Settings app's own
 * pattern (Notifications / Preferences / Bluetooth & Wifi / Account & Info, each
 * a row opening its own sub-page) rather than one flat page of every field. This
 * screen used to hold the server fields and toggles directly; split out once it
 * outgrew that (see ServerSettingsScreen.kt / PreferencesScreen.kt).
 */
class SettingsScreen(activity: SealedLightActivity) : LightScreen<Unit, SettingsScreenViewModel>(activity) {

    override val viewModelClass = SettingsScreenViewModel::class.java

    override fun createViewModel() = SettingsScreenViewModel()

    @Composable
    override fun Content() {
        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth()) {
                SettingsMenuRow("Server") { navigateTo(::ServerSettingsScreen) }
                SettingsMenuRow("Preferences") { navigateTo(::PreferencesScreen) }
            }
        }
    }
}

@Composable
private fun SettingsMenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Heading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    )
}
