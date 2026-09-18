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
import com.musicplus.app.data.LocalDataRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModalManager
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
import kotlin.time.Duration.Companion.seconds

/**
 * Top-level Settings — "Server" is the one thing still split into its own
 * sub-page (ServerSettingsScreen.kt), since a multi-server list with its own
 * add/edit/delete actions doesn't fit a flat row. Everything else used to
 * live on a separate "Preferences" page reached by tapping a row here; folded
 * back in directly, since in practice that page was just this whole app's
 * entire set of remaining toggles/settings anyway — the extra tap in front
 * of all of them wasn't earning its keep.
 */
class SettingsScreenViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val localDataRepository: LocalDataRepository,
    syncQueueRepository: SyncQueueRepository,
) : LightViewModel<Unit>() {

    /** Real count, not a toggle — see SyncQueueRepository (issue #24). Zero means either everything's synced or nothing's ever been queued; either way there's nothing to show. */
    val pendingSyncCount: StateFlow<Int> = syncQueueRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

    fun clearAllLocalData() {
        viewModelScope.launch { localDataRepository.clearAll() }
    }
}

class SettingsScreen(activity: SealedLightActivity) : LightScreen<Unit, SettingsScreenViewModel>(activity) {

    override val viewModelClass = SettingsScreenViewModel::class.java

    override fun createViewModel(): SettingsScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return SettingsScreenViewModel(graph.appSettingsRepository, graph.localDataRepository, graph.syncQueueRepository)
    }

    @Composable
    override fun Content() {
        val showAlbumArtwork by viewModel.showAlbumArtwork.collectAsState()
        val debugLoggingEnabled by viewModel.debugLoggingEnabled.collectAsState()
        val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()
        val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                SettingsMenuRow("Server") { navigateTo(::ServerSettingsScreen) }
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
                LightText(
                    text = "Quality Settings",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { navigateTo(::QualitySettingsScreen) }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )
                // Always last — the least likely to be touched day-to-day.
                ToggleRow(
                    label = "Debug logging",
                    isOn = debugLoggingEnabled,
                    onToggle = { viewModel.toggleDebugLogging() },
                )
                // Not a toggle — a live, real count of writes (favorites,
                // playlist edits) still waiting to reach the server, so a
                // failed/offline write is never just silently identical to a
                // confirmed one (issue #24). Nothing to show, nothing shown.
                if (pendingSyncCount > 0) {
                    LightText(
                        text = if (pendingSyncCount == 1) "1 change waiting to sync" else "$pendingSyncCount changes waiting to sync",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
                LightText(
                    text = "Clear all local data",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            LightModalManager.show(
                                ConfirmModal(
                                    title = "Clear all local data?",
                                    message = buildString {
                                        append(
                                            "Removes downloaded music, cached artwork and lyrics, and the library " +
                                                "cache. Your server login stays saved.",
                                        )
                                        if (pendingSyncCount > 0) {
                                            append(
                                                if (pendingSyncCount == 1) {
                                                    " 1 change waiting to sync will be lost."
                                                } else {
                                                    " $pendingSyncCount changes waiting to sync will be lost."
                                                },
                                            )
                                        }
                                    },
                                    confirmContentDescription = "Clear all local data",
                                    onConfirm = { viewModel.clearAllLocalData() },
                                ),
                                duration = 30.seconds,
                            )
                        }
                        .padding(top = 2f.gridUnitsAsDp()),
                )
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
