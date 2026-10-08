package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.playbackRepository
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What an album does when it is long-pressed, the same wherever albums are listed (the album list, an artist's albums, Favorites, and
 * the album's own artwork): favorite, download the whole album (its state read only once the menu is open, so scrolling a list of
 * albums never asks for it per row), and add it to the queue. The menu was hand-listed in four screens, each with its own copy of the
 * three ViewModel wrappers behind it.
 *
 * Its songs are read through [SelfLoadingTrackList], which refreshes an album before anything acts on it, so "Download album" and
 * "Add album to queue" work for an album never opened on its own screen.
 */
class AlbumActions(
    private val screen: SimpleLightScreen<*>,
    private val activity: SealedLightActivity,
    private val lightContext: SealedLightContext,
) {
    private val graph get() = AppGraph.from(lightContext)

    fun openMenu(albumId: String, name: String, isFavorite: Boolean) {
        screen.navigateTo({ a -> ActionsMenuScreen(activity = a, subtitle = name, items = menuItems(albumId, isFavorite)) })
    }

    private fun menuItems(albumId: String, isFavorite: Boolean): List<ActionMenuItem> {
        val songs = SelfLoadingTrackList.forAlbum(graph.libraryRepository, albumId)
        fun download(state: TrackListDownloadState) =
            trackListDownloadActionItem("album", state) { songs.toggleDownload(lightContext, graph.downloadRepository) }
        return listOf(
            favoriteActionItem(isFavorite) { favorite -> graph.syncQueueRepository.setAlbumFavorite(albumId, favorite) },
            download(TrackListDownloadState.NONE).copy(liveUpdates = songs.observeDownloadState(graph.downloadRepository).map { download(it) }),
            addToQueueActionItem("Add album to queue", playbackRepository(activity, lightContext)) { songs.tracks() },
        )
    }
}

/**
 * What a playlist does when it is long-pressed, the same in the playlist list and inside the playlist: add it to the queue, rename it,
 * edit its order, download it, delete it. (The list's menu and the playlist's own had drifted: the playlist's had no "Add to queue".)
 * Renaming and deleting run on [scope], the ViewModel's: they are started from a result callback, and while the editor is on top the
 * screen is not composed, which cancels its own scope, so a rename launched there silently never ran.
 */
class PlaylistActions(
    private val screen: SimpleLightScreen<*>,
    private val activity: SealedLightActivity,
    private val lightContext: SealedLightContext,
    private val scope: CoroutineScope,
) {
    private val graph get() = AppGraph.from(lightContext)

    /**
     * [editOrder] turns reordering on wherever this is (the list opens the playlist with it armed, the playlist flips it in place);
     * [onDeleted] runs once the playlist is gone (the playlist's own screen closes).
     */
    fun openMenu(playlistId: String, name: String, editOrder: () -> Unit, onDeleted: () -> Unit = {}) {
        screen.navigateTo({ a -> ActionsMenuScreen(activity = a, subtitle = name, items = menuItems(playlistId, name, editOrder, onDeleted)) })
    }

    private fun menuItems(playlistId: String, name: String, editOrder: () -> Unit, onDeleted: () -> Unit): List<ActionMenuItem> {
        val songs = SelfLoadingTrackList.forPlaylist(graph.playlistRepository, playlistId)
        fun download(state: TrackListDownloadState) =
            trackListDownloadActionItem("playlist", state) { songs.toggleDownload(lightContext, graph.downloadRepository) }
        return listOf(
            addToQueueActionItem("Add to queue", playbackRepository(activity, lightContext)) { songs.tracks() },
            ActionMenuItem(
                icon = LightIcons.PENCIL,
                label = "Rename playlist",
                onSelect = ActionMenuSelection.Navigate {
                    screen.navigateTo({ a -> TextEditScreen(a, "Playlist name", name) }) { newName ->
                        if (!newName.isNullOrBlank()) scope.launch { graph.syncQueueRepository.renamePlaylist(playlistId, newName) }
                    }
                },
            ),
            // Navigate, not Perform: this has to leave the menu and land where the reorder handles are.
            ActionMenuItem(icon = LightIcons.REVERSE_ORDER, label = "Edit order", onSelect = ActionMenuSelection.Navigate(editOrder)),
            download(TrackListDownloadState.NONE).copy(liveUpdates = songs.observeDownloadState(graph.downloadRepository).map { download(it) }),
            confirmActionItem(
                icon = LightIcons.TRASH,
                label = "Delete playlist",
                confirmTitle = "Delete \"$name\"?",
                confirmMessage = "This removes the playlist. The tracks themselves aren't affected.",
                confirmContentDescription = "Delete playlist",
                onConfirm = { scope.launch { graph.syncQueueRepository.deletePlaylist(playlistId); onDeleted() } },
            ),
        )
    }
}

/** The [AlbumActions] of the screen this is composed in. */
@Composable
fun SimpleLightScreen<*>.rememberAlbumActions(activity: SealedLightActivity, lightContext: SealedLightContext): AlbumActions =
    remember(this) { AlbumActions(this, activity, lightContext) }

/** The [PlaylistActions] of the screen this is composed in, renaming and deleting on [scope] (the ViewModel's). */
@Composable
fun SimpleLightScreen<*>.rememberPlaylistActions(activity: SealedLightActivity, lightContext: SealedLightContext, scope: CoroutineScope): PlaylistActions =
    remember(this) { PlaylistActions(this, activity, lightContext, scope) }
