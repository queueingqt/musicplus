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
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.DownloadSummary
import com.musicplus.app.data.RemovedServer
import com.musicplus.app.data.ServerLifecycle
import com.musicplus.app.data.ServerProfile
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * List of every saved server (multi-server support) — split out of what used to
 * be a single flat set of URL/username/password fields directly on this screen.
 * Reachable from Settings' "Server" menu row. Tap a row to edit it
 * ([ServerEditScreen]); long-press for turn on / turn off / delete (same
 * [ActionsMenuScreen] pattern used everywhere else in this app); the top bar's
 * ADD icon opens [ServerEditScreen] with no id, to create a new one. A filled dot
 * marks a server that is on (its library shows everywhere), a hollow one a server
 * that is off; the line under the name says which, or when it last synced.
 */
class ServerSettingsScreenViewModel(
    private val serverLifecycle: ServerLifecycle,
) : LightViewModel<Unit>() {

    // AppServerPrefs, not serverConfigRepository directly — this screen gets
    // a fresh ViewModel (and a fresh `.stateIn(...)`) every time it's
    // navigated to, seeded emptyList()/null before the real DataStore Flow
    // catches up — briefly flashed "No servers yet" even with servers
    // already saved. Reported live, 2026-09-18. See AppServerPrefs's own doc.
    val servers: StateFlow<List<ServerProfile>> = AppServerPrefs.servers.value

    val enabledServerIds: StateFlow<Set<String>> = AppServerPrefs.enabledServerIds.value

    val lastSyncedAt: StateFlow<Map<String, Long>> = AppServerPrefs.lastSyncedAt.value
    /** Servers that are on but could not be reached at their last request. */
    val unreachable: StateFlow<Set<String>> = AppServerPrefs.unreachableServerIds.value

    /** Servers removed with their downloads kept: they stay listed below the live ones, until those downloads are deleted. */
    val removedServers: StateFlow<List<RemovedServer>> = AppServerPrefs.removedServers.value

    val downloadSummaries: StateFlow<Map<String, DownloadSummary>> =
        serverLifecycle.downloadSummaries.screenState(viewModelScope, emptyMap())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {}

    fun setEnabled(id: String, on: Boolean) {
        viewModelScope.launch { serverLifecycle.setEnabled(id, on) }
    }

    // On the app's own scope (see ServerLifecycle), not this screen's: leaving the screen must not stop it half way.
    fun remove(id: String, keepDownloads: Boolean) {
        serverLifecycle.remove(id, keepDownloads)
    }

    fun deleteKeptDownloads(id: String) {
        serverLifecycle.deleteKeptDownloads(id)
    }
}

class ServerSettingsScreen(activity: SealedLightActivity) :
    LightScreen<Unit, ServerSettingsScreenViewModel>(activity) {

    override val viewModelClass = ServerSettingsScreenViewModel::class.java

    override fun createViewModel() =
        AppGraph.from(lightContext).let { ServerSettingsScreenViewModel(it.serverLifecycle) }

    @Composable
    override fun Content() {
        val servers by viewModel.servers.collectAsState()
        val enabledServerIds by viewModel.enabledServerIds.collectAsState()
        val lastSyncedAt by viewModel.lastSyncedAt.collectAsState()
        val unreachable by viewModel.unreachable.collectAsState()
        val removedServers by viewModel.removedServers.collectAsState()
        val downloadSummaries by viewModel.downloadSummaries.collectAsState()

        MusicPlusScaffold(
            screen = this,
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
        ) {
            if (servers.isEmpty() && removedServers.isEmpty()) {
                LightText(
                    text = "No servers yet — tap + to add one.",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp()),
                )
            } else {
                // Inside, not the default Outside — see ScrollbarGutter.kt's
                // doc (issue #39): ServerRow's maxLines=1/Ellipsis name and
                // URL are width-dependent, so on Outside they briefly
                // rendered wider (less truncated) on the first frame, then
                // visibly snapped narrower once the scrollbar's real gutter
                // was reserved.
                LightLazyScrollView(
                    modifier = Modifier.fillMaxWidth(),
                    scrollBarPosition = LightScrollBarPosition.Inside,
                    uniformItemHeightGridUnits = 3f,
                ) {
                    items(servers, key = { it.id }) { server ->
                        val isOn = server.id in enabledServerIds
                        ServerRow(
                            server = server,
                            isOn = isOn,
                            status = when {
                                !isOn -> "Off"
                                server.id in unreachable -> "Can't reach server"
                                else -> lastSyncedAt[server.id]?.let { "Synced ${agoText(it)}" } ?: "Not synced yet"
                            },
                            onClick = { navigateTo({ a -> ServerEditScreen(a, serverId = server.id) }) },
                            onLongClick = {
                                navigateTo({ a ->
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = server.name,
                                        items = buildList {
                                            add(serverToggleItem(viewModel, server.id, isOn))
                                            // The delete choices sit in this menu, not behind a second menu: the SDK reuses the
                                            // first ActionsMenuScreen's items for one opened from it.
                                            addAll(serverRemovalItems(server.name, downloadSummaries[server.id]) { keep ->
                                                viewModel.remove(server.id, keep)
                                            })
                                        },
                                    )
                                })
                            },
                        )
                    }
                    items(removedServers, key = { "removed-${it.id}" }) { gone ->
                        val summary = downloadSummaries[gone.id]
                        RemovedServerRow(
                            server = gone,
                            summary = summary,
                            onLongClick = {
                                navigateTo({ a ->
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = "${gone.name} (removed)",
                                        items = listOf(
                                            confirmActionItem(
                                                icon = LightIcons.TRASH,
                                                label = "Delete downloads",
                                                confirmTitle = "Delete downloads?",
                                                confirmMessage = "Removes " + (summary?.let { "${songsText(it.songs)} (${sizeText(it.bytes)})" } ?: "the songs") +
                                                    " that came from \"${gone.name}\" from this phone. This can't be undone.",
                                                confirmContentDescription = "Delete downloads",
                                                onConfirm = { viewModel.deleteKeptDownloads(gone.id) },
                                            ),
                                        ),
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

/**
 * Turn on / Turn off. Once it has run it returns the opposite item, so the row flips in place instead of
 * disappearing (returning null would drop it, and leave whatever is below it, Delete, under the person's thumb).
 */
private fun serverToggleItem(viewModel: ServerSettingsScreenViewModel, serverId: String, isOn: Boolean): ActionMenuItem =
    ActionMenuItem(
        icon = if (isOn) LightIcons.SELECT_OFF else LightIcons.SELECT_ON,
        label = if (isOn) "Turn off" else "Turn on",
        onSelect = ActionMenuSelection.Perform {
            viewModel.setEnabled(serverId, !isOn)
            serverToggleItem(viewModel, serverId, !isOn)
        },
    )

@Composable
private fun ServerRow(server: ServerProfile, isOn: Boolean, status: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onLongClick)
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading state indicator, not trailing — matches QueueScreen's own
        // "now playing" marker (LightIcons.PLAY before the title), the
        // established pattern in this app for "which one of these is the
        // current one" on a list row. Filled = on, hollow = off, so every
        // name lines up whatever its state.
        LightIcon(
            icon = if (isOn) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            size = 1.5f,
            contentDescription = if (isOn) "On" else "Off",
            modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
        )
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = server.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = status, variant = LightTextVariant.Fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * What deleting a server offers. With downloaded songs on the phone the person chooses: keep them (they stay listed and
 * playable, and a new login for the same address picks them up again) or delete them too; the size is in the confirm so
 * it is a real choice. Without any, there is one option. Shared by the Servers list and the edit screen.
 */
internal fun serverRemovalItems(name: String, downloads: DownloadSummary?, onRemove: (keepDownloads: Boolean) -> Unit): List<ActionMenuItem> {
    if (downloads == null || downloads.songs == 0) {
        return listOf(
            confirmActionItem(
                icon = LightIcons.TRASH,
                label = "Delete",
                confirmTitle = "Delete \"$name\"?",
                confirmMessage = "Its library leaves this phone. Nothing on the server is touched.",
                confirmContentDescription = "Delete server",
                onConfirm = { onRemove(false) },
            ),
        )
    }
    val songs = songsText(downloads.songs) + " (${sizeText(downloads.bytes)})"
    return listOf(
        confirmActionItem(
            icon = LightIcons.TRASH,
            label = "Delete, keep downloads",
            confirmTitle = "Delete \"$name\"?",
            confirmMessage = "Its library leaves this phone. The $songs you downloaded " + (if (downloads.songs == 1) "stays" else "stay") + " listed and playable.",
            confirmContentDescription = "Delete server, keep downloads",
            onConfirm = { onRemove(true) },
        ),
        confirmActionItem(
            icon = LightIcons.TRASH,
            label = "Delete with downloads",
            confirmTitle = "Delete \"$name\" and its downloads?",
            confirmMessage = "This also removes $songs from this phone. It can't be undone.",
            confirmContentDescription = "Delete server and downloads",
            onConfirm = { onRemove(false) },
        ),
    )
}

/** A removed server whose downloads were kept: no dot (it has no login), its name and what is still on the phone. */
@Composable
private fun RemovedServerRow(server: RemovedServer, summary: DownloadSummary?, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = "${server.name} (removed)", variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(
                text = summary?.let { "${songsText(it.songs)} · ${sizeText(it.bytes)}" } ?: "Downloads kept",
                variant = LightTextVariant.Fine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
