package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.playbackRepository
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.flow.map

/**
 * What a browsable track does when it is tapped or long-pressed, the same on every screen that lists songs (album, songs, favorites,
 * search, a playlist, an artist's top songs). A screen supplies the tracks, and what is special about its own list as [openMenu]'s
 * `alsoOffer`; it no longer builds the play call or the menu itself, which used to cost one edit per screen for every new behaviour
 * (bb89ece touched 6 screens, 1179db1 7, 11c9c24 12) and let the copies drift (a playlist's menu had no "Add to queue").
 */
class TrackActions(
    private val screen: SimpleLightScreen<*>,
    private val activity: SealedLightActivity,
    private val lightContext: SealedLightContext,
) {
    private val graph get() = AppGraph.from(lightContext)

    /**
     * Tap: plays [tracks] from [index] and opens the player. `playAsync()` updates title and art at once and carries on loading on the
     * playback repository's own scope, so navigating away immediately after is safe.
     */
    fun play(tracks: List<Track>, index: Int = 0, albumArtId: String? = null) {
        playbackRepository(activity, lightContext).playAsync(tracks, index, albumArtId)
        screen.navigateTo(::PlayerScreen)
    }

    /** Long-press: opens the track's menu, [alsoOffer] after the rows every track has. */
    fun openMenu(track: Track, alsoOffer: List<ActionMenuItem> = emptyList()) {
        screen.navigateTo({ a -> ActionsMenuScreen(activity = a, subtitle = track.title, items = menuItems(track) + alsoOffer) })
    }

    /** The rows every track has: favorite, add to queue, add to a playlist, download (live while the menu is open). */
    fun menuItems(track: Track): List<ActionMenuItem> = listOf(
        favoriteActionItem(track.isFavorite) { favorite -> graph.syncQueueRepository.setTrackFavorite(track.id, favorite) },
        addToQueueActionItem("Add to queue", playbackRepository(activity, lightContext)) { listOf(track) },
        ActionMenuItem(
            icon = LightIcons.LIST,
            label = "Add to playlist",
            onSelect = ActionMenuSelection.Navigate { screen.navigateTo({ a -> PlaylistPickerScreen(a, track.id) }) },
        ),
        downloadItem(track),
    )

    private fun downloadItem(track: Track): ActionMenuItem {
        val downloads = graph.downloadRepository
        fun row(status: com.musicplus.app.data.DownloadStatus?) =
            trackDownloadActionItem(status) { shown -> downloads.tap(lightContext, track, shown) }
        return row(track.downloadStatus).copy(liveUpdates = downloads.observeStatus(track.id).map { row(it?.status) })
    }
}

/** The [TrackActions] of the screen this is composed in. */
@Composable
fun SimpleLightScreen<*>.rememberTrackActions(activity: SealedLightActivity, lightContext: SealedLightContext): TrackActions =
    remember(this) { TrackActions(this, activity, lightContext) }
