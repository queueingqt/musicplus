package com.musicplus.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ListRefresher
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.PlaylistHomes
import com.musicplus.app.data.PlaylistRepository
import com.musicplus.app.data.ServerScope
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch

class PlaylistListScreenViewModel(
    private val playlistRepository: PlaylistRepository,
    private val syncQueueRepository: SyncQueueRepository,
    listRefresher: ListRefresher,
) : CachedListViewModel(listRefresher, ListRefresher.Target.PLAYLISTS) {

    // See AppLibraryCache's doc — reads the already-live, process-lifetime cache instead of re-subscribing to observePlaylists() on every
    // fresh per-visit ViewModel. A client-side filter: no server-side playlist name search worth a round trip for a realistically short list.
    val filter = ListFilter(viewModelScope)
    val playlists = filter.narrow(AppLibraryCache.playlists.value) { playlist, query -> playlist.name.containsIgnoringCase(query) }

    /**
     * Makes a playlist at [home] (a server id, or Phone Only) and gives its id to [onCreated] — real if it synced
     * immediately, a local placeholder if it's now queued (see SyncQueueRepository), or null if that server is not set up.
     *
     * Runs on [viewModelScope], not the screen's `rememberCoroutineScope()`: this is called from the result callback of the
     * name editor (and the "Save to" list), and while those are on top this screen is not composed, which cancels its
     * scope. A create launched there never ran, so a new playlist silently did not appear. [onCreated] runs on the main thread.
     */
    fun createPlaylist(name: String, home: String, onCreated: (String?) -> Unit) {
        viewModelScope.launch {
            val id = syncQueueRepository.createPlaylist(name, home)
            onCreated(id)
            if (home != ServerScope.PHONE) playlistRepository.refreshPlaylists()
        }
    }
}

class PlaylistListScreen(private val activity: SealedLightActivity) :
    LightScreen<Unit, PlaylistListScreenViewModel>(activity) {

    override val viewModelClass = PlaylistListScreenViewModel::class.java

    override fun createViewModel(): PlaylistListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return PlaylistListScreenViewModel(graph.playlistRepository, graph.syncQueueRepository, graph.listRefresher)
    }

    @Composable
    override fun Content() {
        val query by viewModel.filter.query.collectAsState()
        val playlists by viewModel.playlists.collectAsState()
        val playlistActions = rememberPlaylistActions(activity, lightContext, viewModel.viewModelScope)

        MusicPlusScaffold(
            screen = this,
            topBar = {
                // Custom top bar, not LightTopBar — that only has room for one
                // rightButton, and this needs two (search, new playlist).
                PlaylistListTopBar(
                    onBack = { goBack() },
                    onSearch = { navigateTo({ a -> TextEditScreen(a, "Search playlists", query) }) { result -> viewModel.filter.set(result) } },
                    onNewPlaylist = {
                        navigateTo({ a -> TextEditScreen(a, "Playlist name", "") }) { name ->
                            if (!name.isNullOrBlank()) {
                                val open = { id: String? -> if (id != null) navigateTo({ a -> PlaylistDetailScreen(a, id) }) }
                                // A playlist that starts from nothing has no server to belong to yet, so the person picks
                                // where it is saved: any server that is on and can keep playlists, or Phone Only. When
                                // Phone Only is the only place there is, there is nothing to ask.
                                val choices = PlaylistHomes.choices()
                                if (choices.size == 1) {
                                    viewModel.createPlaylist(name.trim(), choices.first().id, open)
                                } else {
                                    navigateTo({ a -> SaveToScreen(a, choices) }) { home ->
                                        if (home != null) viewModel.createPlaylist(name.trim(), home, open)
                                    }
                                }
                            }
                        }
                    },
                )
            },
        ) {
            if (playlists.isEmpty()) EmptyListNote("playlists", query)
            ScreenList(viewModel.scrollPosition) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { navigateTo({ a -> PlaylistDetailScreen(a, playlist.id) }) },
                        onOpenActions = {
                            playlistActions.openMenu(
                                playlist.id,
                                playlist.name,
                                // Navigates in with reorder mode already active, rather than needing a second long-press once there:
                                // this screen has no track list of its own to show handles on.
                                editOrder = { navigateTo({ a -> PlaylistDetailScreen(a, playlist.id, startInReorderMode = true) }) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onOpenActions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions)
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
    ) {
        LightText(text = playlist.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LightText(text = playlist.detailLine, variant = LightTextVariant.Fine)
    }
}

private const val TOPBAR_HEIGHT_UNITS = 3f
private const val HORIZONTAL_PADDING_UNITS = 1f
private const val CENTER_MAX_WIDTH_UNITS = 18f

/**
 * Custom top bar, not LightTopBar — that only has room for one rightButton, and
 * this screen needs two (search, new playlist) alongside the back button.
 * Replicates LightTopBar's own Box + Row(zIndex 2f) + separately-centered-title
 * Box structure (sdk/ui/.../LightTopBar.kt) rather than approximating it, so
 * this bar's height/padding/title placement matches every other screen's top
 * bar exactly. LightBarButtonView (what LightTopBar uses internally to render
 * a button) is `internal` to :sdk:ui and not visible here, so the icons below
 * are built directly from the public LightIcon composable instead.
 */
@Composable
private fun PlaylistListTopBar(onBack: () -> Unit, onSearch: () -> Unit, onNewPlaylist: () -> Unit) {
    val barHeight = TOPBAR_HEIGHT_UNITS.gridUnitsAsDp()
    val horizontalPadding = HORIZONTAL_PADDING_UNITS.gridUnitsAsDp()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = horizontalPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .zIndex(2f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightIcon(
                icon = LightIcons.BACK,
                contentDescription = "Back",
                modifier = Modifier.lightClickable(onClick = onBack),
            )
            Box(modifier = Modifier.weight(1f))
            LightIcon(
                icon = LightIcons.ADD,
                contentDescription = "New playlist",
                modifier = Modifier
                    .lightClickable(onClick = onNewPlaylist)
                    .padding(end = 0.5f.gridUnitsAsDp()),
            )
            LightIcon(
                icon = LightIcons.SEARCH,
                contentDescription = "Search playlists",
                modifier = Modifier.lightClickable(onClick = onSearch),
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(barHeight),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "Playlists",
                variant = LightTextVariant.Fine,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = CENTER_MAX_WIDTH_UNITS.gridUnitsAsDp()),
            )
        }
    }
}
