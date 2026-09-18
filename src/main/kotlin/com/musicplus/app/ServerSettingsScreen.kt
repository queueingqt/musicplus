package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ServerConfigRepository
import com.musicplus.app.data.ServerProfile
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * List of every saved server (multi-server support) — split out of what used to
 * be a single flat set of URL/username/password fields directly on this screen.
 * Reachable from Settings' "Server" menu row. Tap a row to edit it
 * ([ServerEditScreen]); long-press for set-active/delete (same
 * [ActionsMenuScreen] pattern used everywhere else in this app); the top bar's
 * ADD icon opens [ServerEditScreen] with no id, to create a new one.
 */
class ServerSettingsScreenViewModel(
    private val serverConfigRepository: ServerConfigRepository,
) : LightViewModel<Unit>() {

    val servers: StateFlow<List<ServerProfile>> = serverConfigRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeServerId: StateFlow<String?> = serverConfigRepository.activeServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {}

    fun setActive(id: String) {
        viewModelScope.launch {
            serverConfigRepository.setActive(id)
            AppGraph.invalidateApi()
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            serverConfigRepository.remove(id)
            AppGraph.invalidateApi()
        }
    }
}

class ServerSettingsScreen(activity: SealedLightActivity) :
    LightScreen<Unit, ServerSettingsScreenViewModel>(activity) {

    override val viewModelClass = ServerSettingsScreenViewModel::class.java

    override fun createViewModel() =
        ServerSettingsScreenViewModel(AppGraph.from(lightContext).serverConfigRepository)

    @Composable
    override fun Content() {
        val servers by viewModel.servers.collectAsState()
        val activeServerId by viewModel.activeServerId.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Servers"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        onClick = { navigateTo({ a -> ServerEditScreen(a, serverId = null) }) },
                        contentDescription = "Add server",
                    ),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            if (servers.isEmpty()) {
                LightText(
                    text = "No servers yet — tap + to add one.",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp()),
                )
            } else {
                LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                    items(servers, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            isActive = server.id == activeServerId,
                            onClick = { navigateTo({ a -> ServerEditScreen(a, serverId = server.id) }) },
                            onLongClick = {
                                navigateTo({ a ->
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = server.name,
                                        items = buildList {
                                            if (server.id != activeServerId) {
                                                add(
                                                    ActionMenuItem(
                                                        icon = LightIcons.SELECT_ON,
                                                        label = "Set active",
                                                        onSelect = ActionMenuSelection.Perform { viewModel.setActive(server.id) },
                                                    ),
                                                )
                                            }
                                            add(
                                                ActionMenuItem(
                                                    icon = LightIcons.TRASH,
                                                    label = "Delete",
                                                    onSelect = ActionMenuSelection.Perform { viewModel.remove(server.id) },
                                                ),
                                            )
                                        },
                                    )
                                })
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(server: ServerProfile, isActive: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading state indicator, not trailing — matches QueueScreen's own
        // "now playing" marker (LightIcons.PLAY before the title), the
        // established pattern in this app for "which one of these is the
        // current one" on a list row.
        if (isActive) {
            LightIcon(
                icon = LightIcons.SELECT_ON,
                size = 1.5f,
                contentDescription = "Active server",
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = server.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = server.baseUrl, variant = LightTextVariant.Fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
