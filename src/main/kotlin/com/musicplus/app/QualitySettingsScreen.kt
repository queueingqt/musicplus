package com.musicplus.app

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
 * Issue #7's follow-up: the bitrate cap is a plain kbps `Int?` (`null` =
 * original, uncapped), not a fixed enum — see [StreamQuality]'s doc, and
 * [AppSettingsRepository]'s streamQualityWifi/streamQualityCellular/
 * downloadQuality for the three independent settings this screen edits.
 * Split Wi-Fi from cellular because cellular data is more often limited than
 * Wi-Fi; split downloads from both because an explicit "Download" tap is
 * already a deliberate "keep this offline" choice most people want at full
 * quality regardless of whatever streaming default they've picked.
 */
class StreamingQualityScreenViewModel(
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

class StreamingQualityScreen(activity: SealedLightActivity) :
    LightScreen<Unit, StreamingQualityScreenViewModel>(activity) {

    override val viewModelClass = StreamingQualityScreenViewModel::class.java

    override fun createViewModel() = StreamingQualityScreenViewModel(AppGraph.from(lightContext).appSettingsRepository)

    @Composable
    override fun Content() {
        val wifiQuality by viewModel.wifiQuality.collectAsState()
        val cellularQuality by viewModel.cellularQuality.collectAsState()
        val downloadQuality by viewModel.downloadQuality.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Streaming quality"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                QualitySection(
                    title = "Wi-Fi",
                    current = wifiQuality,
                    onSelect = viewModel::setWifiQuality,
                    onCustom = {
                        navigateTo({ a -> TextEditScreen(a, "Wi-Fi quality (kbps)", wifiQuality?.toString().orEmpty()) }) { result ->
                            result.toIntOrNull()?.takeIf { it > 0 }?.let(viewModel::setWifiQuality)
                        }
                    },
                )
                QualitySection(
                    title = "Cellular",
                    current = cellularQuality,
                    onSelect = viewModel::setCellularQuality,
                    onCustom = {
                        navigateTo({ a -> TextEditScreen(a, "Cellular quality (kbps)", cellularQuality?.toString().orEmpty()) }) { result ->
                            result.toIntOrNull()?.takeIf { it > 0 }?.let(viewModel::setCellularQuality)
                        }
                    },
                )
                QualitySection(
                    title = "Downloads",
                    current = downloadQuality,
                    onSelect = viewModel::setDownloadQuality,
                    onCustom = {
                        navigateTo({ a -> TextEditScreen(a, "Download quality (kbps)", downloadQuality?.toString().orEmpty()) }) { result ->
                            result.toIntOrNull()?.takeIf { it > 0 }?.let(viewModel::setDownloadQuality)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QualitySection(title: String, current: Int?, onSelect: (Int?) -> Unit, onCustom: () -> Unit) {
    SectionHeader(title)
    for (preset in STREAM_QUALITY_PRESETS) {
        QualityRow(
            label = "$preset kbps",
            selected = current == preset,
            onClick = { onSelect(preset) },
        )
    }
    QualityRow(
        label = "Original",
        selected = current == null,
        onClick = { onSelect(null) },
    )
    // A value that isn't null and isn't one of the presets can only have
    // gotten there through this same row, so it's the current custom value,
    // not a placeholder — labeled with the number rather than a generic
    // "Custom" so it reads as "this is what's set," not "tap to configure."
    val isCustom = current != null && current !in STREAM_QUALITY_PRESETS
    QualityRow(
        label = if (isCustom) "Custom ($current kbps)" else "Custom…",
        selected = isCustom,
        onClick = onCustom,
    )
}

@Composable
private fun SectionHeader(title: String) {
    LightText(
        text = title,
        variant = LightTextVariant.Heading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

// Leading state indicator, not trailing — same "which one of these is
// current" pattern as ServerSettingsScreen's ServerRow (LightIcons.SELECT_ON
// before the label), the established convention in this app.
@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            LightIcon(
                icon = LightIcons.SELECT_ON,
                size = 1.5f,
                contentDescription = "Selected",
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}
