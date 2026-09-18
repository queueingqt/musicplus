package com.musicplus.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppSettingsRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide display/debug toggles, split out of the main SettingsScreen — see
 * ServerSettingsScreen.kt's file doc for why (SettingsScreen was outgrowing one
 * flat page; this follows the LightOS system Settings app's own pattern of a
 * top-level menu with sub-pages per category).
 */
class PreferencesScreenViewModel(
    private val appSettingsRepository: AppSettingsRepository,
) : LightViewModel<Unit>() {

    val showAlbumArtwork: StateFlow<Boolean> = appSettingsRepository.showAlbumArtwork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun toggleShowAlbumArtwork() {
        viewModelScope.launch { appSettingsRepository.setShowAlbumArtwork(!showAlbumArtwork.value) }
    }

    val debugLoggingEnabled: StateFlow<Boolean> = appSettingsRepository.debugLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun toggleDebugLogging() {
        viewModelScope.launch { appSettingsRepository.setDebugLoggingEnabled(!debugLoggingEnabled.value) }
    }

    val hapticFeedbackEnabled: StateFlow<Boolean> = appSettingsRepository.hapticFeedbackEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun toggleHapticFeedback() {
        viewModelScope.launch { appSettingsRepository.setHapticFeedbackEnabled(!hapticFeedbackEnabled.value) }
    }
}

class PreferencesScreen(activity: SealedLightActivity) :
    LightScreen<Unit, PreferencesScreenViewModel>(activity) {

    override val viewModelClass = PreferencesScreenViewModel::class.java

    override fun createViewModel() =
        PreferencesScreenViewModel(AppGraph.from(lightContext).appSettingsRepository)

    @Composable
    override fun Content() {
        val showAlbumArtwork by viewModel.showAlbumArtwork.collectAsState()
        val debugLoggingEnabled by viewModel.debugLoggingEnabled.collectAsState()
        val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Preferences"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                ToggleRow(
                    label = "Show album artwork",
                    isOn = showAlbumArtwork,
                    onToggle = { viewModel.toggleShowAlbumArtwork() },
                )
                ToggleRow(
                    label = "Haptic feedback",
                    isOn = hapticFeedbackEnabled,
                    onToggle = { viewModel.toggleHapticFeedback() },
                )
                // Always last — the least likely to be touched day-to-day.
                ToggleRow(
                    label = "Debug logging",
                    isOn = debugLoggingEnabled,
                    onToggle = { viewModel.toggleDebugLogging() },
                )
            }
        }
    }
}

// Icon on the left, before the label — not trailing. Confirmed against the
// phone's own LightOS Settings app (General > Haptic Feedback): its toggle
// icon leads the label the same way Airplane Mode's key icon does.
@Composable
private fun ToggleRow(label: String, isOn: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onToggle)
            .padding(vertical = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (isOn) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
            size = 1.5f,
            contentDescription = if (isOn) "On" else "Off",
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
    }
}
