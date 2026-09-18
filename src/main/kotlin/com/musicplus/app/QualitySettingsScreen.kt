package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppSettingsRepository
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Issue #7's follow-up: the bitrate cap is a plain kbps `Int?` (`null` =
 * original, uncapped), not a fixed enum — see [StreamQuality]'s doc, and
 * [AppSettingsRepository]'s streamQualityWifi/streamQualityCellular/
 * downloadQuality for the three independent settings this screen edits.
 * Split Wi-Fi from cellular because cellular data is more often limited than
 * Wi-Fi; split downloads from both because an explicit "Download" tap is
 * already a deliberate "keep this offline" choice most people want at full
 * quality regardless of whatever streaming default they've picked.
 *
 * A menu of the three settings, not a flat list of all their picker rows
 * inline — the 3-picker single-screen version, one right below the other,
 * read as one unwieldy 18-row wall with no clear grouping. Drill-down (this
 * screen picks *which* setting, [QualityPickerScreen] picks its value) is
 * the same shape Settings > Server already uses.
 */
class QualitySettingsScreenViewModel(
    private val appSettingsRepository: AppSettingsRepository,
) : LightViewModel<Unit>() {

    val wifiQuality: StateFlow<Int?> = appSettingsRepository.streamQualityWifi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cellularQuality: StateFlow<Int?> = appSettingsRepository.streamQualityCellular
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val downloadQuality: StateFlow<Int?> = appSettingsRepository.downloadQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWifiQuality(maxBitRateKbps: Int?) = viewModelScope.launch { appSettingsRepository.setStreamQualityWifi(maxBitRateKbps) }
    fun setCellularQuality(maxBitRateKbps: Int?) = viewModelScope.launch { appSettingsRepository.setStreamQualityCellular(maxBitRateKbps) }
    fun setDownloadQuality(maxBitRateKbps: Int?) = viewModelScope.launch { appSettingsRepository.setDownloadQuality(maxBitRateKbps) }
}

class QualitySettingsScreen(activity: SealedLightActivity) :
    LightScreen<Unit, QualitySettingsScreenViewModel>(activity) {

    override val viewModelClass = QualitySettingsScreenViewModel::class.java

    override fun createViewModel() = QualitySettingsScreenViewModel(AppGraph.from(lightContext).appSettingsRepository)

    @Composable
    override fun Content() {
        val wifiQuality by viewModel.wifiQuality.collectAsState()
        val cellularQuality by viewModel.cellularQuality.collectAsState()
        val downloadQuality by viewModel.downloadQuality.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Quality Settings"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                QualityMenuRow(label = "Wi-Fi", current = wifiQuality) {
                    navigateTo({ a -> QualityPickerScreen(a, "Wi-Fi quality", wifiQuality) }) { selection ->
                        viewModel.setWifiQuality(selection.maxBitRateKbps)
                    }
                }
                QualityMenuRow(label = "Cellular", current = cellularQuality) {
                    navigateTo({ a -> QualityPickerScreen(a, "Cellular quality", cellularQuality) }) { selection ->
                        viewModel.setCellularQuality(selection.maxBitRateKbps)
                    }
                }
                QualityMenuRow(label = "Downloads", current = downloadQuality) {
                    navigateTo({ a -> QualityPickerScreen(a, "Download quality", downloadQuality) }) { selection ->
                        viewModel.setDownloadQuality(selection.maxBitRateKbps)
                    }
                }
            }
        }
    }
}

// Subtitle shows the live current value — same "second line under the
// label" pattern ServerSettingsScreen's ServerRow uses for a server's
// baseUrl, so this reads as "here's what's set" without opening the picker.
@Composable
private fun QualityMenuRow(label: String, current: Int?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Copy)
        LightText(text = streamQualityLabel(current), variant = LightTextVariant.Fine, lighten = true)
    }
}
