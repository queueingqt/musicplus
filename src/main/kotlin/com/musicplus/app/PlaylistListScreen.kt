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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.PlaylistRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistListScreenViewModel(
    private val playlistRepository: PlaylistRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val downloadRepository: DownloadRepository,
) : LightViewModel<Unit>() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val allPlaylists = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Client-side filter, same reasoning as AlbumListScreen/SearchScreen: no
    // server-side playlist name search in the Subsonic API worth round-tripping for
    // what's realistically a short list.
    val playlists: StateFlow<List<Playlist>> = combine(allPlaylists, _query) { playlists, q ->
        if (q.isBlank()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { playlistRepository.refreshPlaylists() }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** Returns the new playlist's id — real if it synced immediately, a local placeholder if it's now queued (see SyncQueueRepository), or null only if no server is configured at all. */
    suspend fun createPlaylist(name: String): String? {
        val id = syncQueueRepository.createPlaylist(name)
        playlistRepository.refreshPlaylists()
        return id
    }

    // See SelfLoadingTrackList.kt — shared with the album-level download rows,
    // just sourced from a playlist's own tracks instead. Same root cause as
    // AlbumListScreenViewModel's toggleAlbumDownload used to have: playlistRepository.observeTracks'
    // Room cache was only ever populated by PlaylistDetailScreen's own
    // onScreenShow, so choosing "Download playlist" here without ever opening
    // that playlist first silently enqueued nothing.
    fun playlistDownloadState(playlistId: String): Flow<TrackListDownloadState> =
        SelfLoadingTrackList.forPlaylist(playlistRepository, playlistId).observeDownloadState(downloadRepository)

    suspend fun togglePlaylistDownload(lightContext: SealedLightContext, playlistId: String): TrackListDownloadState =
        SelfLoadingTrackList.forPlaylist(playlistRepository, playlistId).toggleDownload(lightContext, downloadRepository)

    // Same actions as PlaylistDetailScreenViewModel's own rename/delete —
    // reported live: this list's long-press menu only offered Download,
    // unlike the one reached from inside a playlist, which also had
    // Rename/Edit order/Delete. ("Edit order" itself doesn't need a
    // ViewModel-side method here: it just navigates into PlaylistDetailScreen
    // with reorder mode pre-armed, since reordering needs the track list
    // actually on screen to show handles on.)
    fun rename(playlistId: String, name: String) {
        viewModelScope.launch { syncQueueRepository.renamePlaylist(playlistId, name) }
    }

    fun delete(playlistId: String) {
        viewModelScope.launch { syncQueueRepository.deletePlaylist(playlistId) }
    }

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

class PlaylistListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, PlaylistListScreenViewModel>(activity) {

    override val viewModelClass = PlaylistListScreenViewModel::class.java

    override fun createViewModel(): PlaylistListScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return PlaylistListScreenViewModel(graph.playlistRepository, graph.syncQueueRepository, graph.downloadRepository)
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val query by viewModel.query.collectAsState()
        val playlists by viewModel.playlists.collectAsState()

        MusicPlusScaffold(
            topBar = {
                // Custom top bar, not LightTopBar — that only has room for one
                // rightButton, and this needs two (search, new playlist).
                PlaylistListTopBar(
                    onBack = { goBack() },
                    onSearch = {
                        navigateTo({ a -> TextEditScreen(a, "Search playlists", query) }) { result ->
                            viewModel.onQueryChange(result)
                        }
                    },
                    onNewPlaylist = {
                        navigateTo({ a -> TextEditScreen(a, "Playlist name", "") }) { name ->
                            if (!name.isNullOrBlank()) {
                                scope.launch {
                                    val id = viewModel.createPlaylist(name)
                                    if (id != null) navigateTo({ a -> PlaylistDetailScreen(a, id) })
                                }
                            }
                        }
                    },
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
            // Inside, not the default Outside — see ScrollbarGutter.kt's doc
            // (issue #39): PlaylistRow's maxLines=1/Ellipsis name is
            // width-dependent, so on Outside it briefly rendered wider (less
            // truncated) on the first frame, then visibly snapped narrower
            // once the scrollbar's real gutter was reserved.
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 3f,
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { navigateTo({ a -> PlaylistDetailScreen(a, playlist.id) }) },
                        onOpenActions = {
                            navigateTo({ a ->
                                // Same 4 items, same order, as PlaylistDetailScreen's own
                                // long-press menu — reported live: this list's menu only had
                                // Download, unlike the one reached from inside a playlist.
                                val deleteItem = confirmActionItem(
                                    icon = LightIcons.TRASH,
                                    label = "Delete playlist",
                                    confirmTitle = "Delete \"${playlist.name}\"?",
                                    confirmMessage = "This removes the playlist. The tracks themselves aren't affected.",
                                    confirmContentDescription = "Delete playlist",
                                    onConfirm = { viewModel.delete(playlist.id) },
                                )
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = playlist.name,
                                    items = listOf(
                                        ActionMenuItem(
                                            icon = LightIcons.PENCIL,
                                            label = "Rename playlist",
                                            onSelect = ActionMenuSelection.Navigate {
                                                navigateTo({ a2 -> TextEditScreen(a2, "Playlist name", playlist.name) }) { newName ->
                                                    if (!newName.isNullOrBlank()) viewModel.rename(playlist.id, newName)
                                                }
                                            },
                                        ),
                                        ActionMenuItem(
                                            icon = LightIcons.REVERSE_ORDER,
                                            label = "Edit order",
                                            // Navigates in with reorder mode already active, rather
                                            // than needing a second long-press once there — this
                                            // screen has no track list of its own to show handles on.
                                            onSelect = ActionMenuSelection.Navigate {
                                                navigateTo({ a2 -> PlaylistDetailScreen(a2, playlist.id, startInReorderMode = true) })
                                            },
                                        ),
                                        // Real state only starts being read once this menu is actually
                                        // open (via liveUpdates below) — see AlbumListScreen's identical
                                        // fix for why this used to be collected per-row in the list itself.
                                        trackListDownloadActionItem("playlist", TrackListDownloadState.NONE) {
                                            viewModel.togglePlaylistDownload(lightContext, playlist.id)
                                        }.copy(
                                            liveUpdates = viewModel.playlistDownloadState(playlist.id).map { s ->
                                                trackListDownloadActionItem("playlist", s) {
                                                    viewModel.togglePlaylistDownload(lightContext, playlist.id)
                                                }
                                            },
                                        ),
                                        deleteItem,
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
        LightText(text = "${playlist.songCount} tracks", variant = LightTextVariant.Fine)
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
