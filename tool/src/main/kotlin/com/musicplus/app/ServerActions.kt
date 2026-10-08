package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.musicplus.app.data.DownloadSummary
import com.musicplus.app.data.RemovedServer
import com.musicplus.app.data.ServerProfile
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcons

/**
 * What a server's long-press menu offers, in one place, the same wherever it is opened: turn it on or off, and the delete choices (the
 * Servers list and the Edit server screen both open the delete choices; a server removed with its downloads kept has its own one-row menu).
 * These were hand-listed in `ServerSettingsScreen` and `ServerEditScreen`; the rows and their wording are pure functions below, so they are
 * tested without a screen. The actions themselves are passed in: what a tap does is the ViewModel's (it outlives the menu).
 */
class ServerActions(private val screen: SimpleLightScreen<*>) {
    /** A saved server's menu: turn on/off, then the delete choices. The delete choices sit in this menu, not behind a second one: the SDK reuses the first ActionsMenuScreen's items for one opened from it. */
    fun openServerMenu(
        server: ServerProfile,
        isOn: Boolean,
        downloads: DownloadSummary?,
        setEnabled: (Boolean) -> Unit,
        remove: (keepDownloads: Boolean) -> Unit,
    ) {
        screen.navigateTo({ a -> ActionsMenuScreen(activity = a, subtitle = server.name, items = serverMenuItems(server.name, isOn, downloads, setEnabled, remove)) })
    }

    /** The delete choices alone, for the Edit server screen. */
    fun openRemovalMenu(name: String, downloads: DownloadSummary?, remove: (keepDownloads: Boolean) -> Unit) {
        screen.navigateTo({ a -> ActionsMenuScreen(activity = a, subtitle = "Delete \"$name\"", items = serverRemovalItems(name, downloads, remove)) })
    }

    /** A removed server whose downloads were kept: the one thing to do is delete them. */
    fun openRemovedMenu(gone: RemovedServer, summary: DownloadSummary?, deleteDownloads: () -> Unit) {
        screen.navigateTo({ a -> ActionsMenuScreen(activity = a, subtitle = "${gone.name} (removed)", items = removedServerItems(gone.name, summary, deleteDownloads)) })
    }
}

/** The [ServerActions] of the screen this is composed in. */
@Composable
fun SimpleLightScreen<*>.rememberServerActions(): ServerActions = remember(this) { ServerActions(this) }

/** Turn on / turn off, then the delete choices. */
internal fun serverMenuItems(
    name: String,
    isOn: Boolean,
    downloads: DownloadSummary?,
    setEnabled: (Boolean) -> Unit,
    remove: (keepDownloads: Boolean) -> Unit,
): List<ActionMenuItem> = listOf(serverToggleItem(isOn, setEnabled)) + serverRemovalItems(name, downloads, remove)

/**
 * Turn on / Turn off. Once it has run it returns the opposite item, so the row flips in place instead of
 * disappearing (returning null would drop it, and leave whatever is below it, Delete, under the person's thumb).
 */
internal fun serverToggleItem(isOn: Boolean, setEnabled: (Boolean) -> Unit): ActionMenuItem =
    ActionMenuItem(
        key = "toggle",
        icon = if (isOn) LightIcons.SELECT_OFF else LightIcons.SELECT_ON,
        label = if (isOn) "Turn off" else "Turn on",
        onSelect = ActionMenuSelection.Perform {
            setEnabled(!isOn)
            serverToggleItem(!isOn, setEnabled)
        },
    )

/** What the "Delete, keep downloads" confirm says. */
internal fun keepDownloadsMessage(downloads: DownloadSummary): String =
    "Its library leaves this phone. The ${songsAndSize(downloads)} you downloaded " + (if (downloads.songs == 1) "stays" else "stay") + " listed and playable."

/** What the "Delete with downloads" confirm says. */
internal fun deleteWithDownloadsMessage(downloads: DownloadSummary): String =
    "This also removes ${songsAndSize(downloads)} from this phone. It can't be undone."

private fun songsAndSize(downloads: DownloadSummary) = songsText(downloads.songs) + " (${sizeText(downloads.bytes)})"

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
    return listOf(
        confirmActionItem(
            icon = LightIcons.TRASH,
            label = "Delete, keep downloads",
            confirmTitle = "Delete \"$name\"?",
            confirmMessage = keepDownloadsMessage(downloads),
            confirmContentDescription = "Delete server, keep downloads",
            onConfirm = { onRemove(true) },
        ),
        confirmActionItem(
            icon = LightIcons.TRASH,
            label = "Delete with downloads",
            confirmTitle = "Delete \"$name\" and its downloads?",
            confirmMessage = deleteWithDownloadsMessage(downloads),
            confirmContentDescription = "Delete server and downloads",
            onConfirm = { onRemove(false) },
        ),
    )
}

/** What "Delete downloads" for a removed server says, with what is left on the phone when it is known. */
internal fun deleteKeptDownloadsMessage(name: String, summary: DownloadSummary?): String =
    "Removes " + (summary?.let { songsAndSize(it) } ?: "the songs") + " that came from \"$name\" from this phone. This can't be undone."

/** The one row of a removed server's menu. */
internal fun removedServerItems(name: String, summary: DownloadSummary?, onDeleteDownloads: () -> Unit): List<ActionMenuItem> = listOf(
    confirmActionItem(
        icon = LightIcons.TRASH,
        label = "Delete downloads",
        confirmTitle = "Delete downloads?",
        confirmMessage = deleteKeptDownloadsMessage(name, summary),
        confirmContentDescription = "Delete downloads",
        onConfirm = onDeleteDownloads,
    ),
)
