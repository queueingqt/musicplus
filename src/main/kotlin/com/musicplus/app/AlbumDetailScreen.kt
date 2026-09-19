package com.musicplus.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadEntity
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.playbackRepository
import com.musicplus.app.data.SyncQueueRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumDetailScreenViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val albumId: String,
    initialAlbum: Album?,
) : LightViewModel<Unit>() {

    // See SelfLoadingTrackList.kt (shared with AlbumListScreen/ArtistDetailScreen's
    // own album-level download rows, and PlaylistListScreen's playlist ones) —
    // both [tracks] and [albumDownloadState]/[toggleAlbumDownload] below read
    // through this one wrapper now, instead of [tracks] reading the raw
    // Room-cache flow directly — this used to be the one call site that
    // bypassed the refresh-then-read guarantee the wrapper exists to enforce.
    private val selfLoadingTracks = SelfLoadingTrackList.forAlbum(libraryRepository, albumId)

    val tracks: StateFlow<List<Track>> = selfLoadingTracks.observeTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Seeded from whatever the caller already had in hand (e.g. the row a list
    // screen just tapped) rather than always starting at null. Room's Flow here
    // is genuinely async — even though the row is already local, StateFlow has
    // no synchronous "peek" and reports its initial value for at least the
    // first frame — so without this seed, title AND art visibly flashed to
    // their empty/placeholder state on every single navigation into this
    // screen, confirmed live on-device 2026-09-18. Reconciles with the real
    // Flow as soon as it emits, same as before.
    val album: StateFlow<Album?> = libraryRepository.observeAlbums()
        .map { albums -> albums.find { it.id == albumId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialAlbum)

    val albumDownloadState: StateFlow<TrackListDownloadState> =
        selfLoadingTracks.observeDownloadState(downloadRepository)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackListDownloadState.NONE)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { selfLoadingTracks.refreshNow() }
    }

    fun downloadStatus(songId: String): Flow<DownloadEntity?> = downloadRepository.observeStatus(songId)

    /**
     * This button is now the only download control (no separate Downloads screen —
     * status lives inline per track everywhere), so it has to do double duty:
     * enqueue when there's nothing downloaded/in-flight, and stop/remove otherwise.
     * `DownloadRepository.cancel` already deletes both the local file and the DB
     * row regardless of job state, so it doubles as "remove local copy" for a
     * COMPLETE download, not just "abort an in-flight one". Suspend and returns
     * the resulting status (rather than fire-and-forget) so the action menu row
     * that triggers this can update itself in place afterward.
     */
    suspend fun toggleDownload(lightContext: SealedLightContext, track: Track, currentStatus: DownloadStatus?): DownloadStatus? =
        when (currentStatus) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETE -> {
                downloadRepository.cancel(lightContext, track.id)
                null
            }
            DownloadStatus.FAILED, null -> {
                downloadRepository.enqueue(lightContext, track)
                DownloadStatus.QUEUED
            }
        }

    /** See SelfLoadingTrackList.kt's [SelfLoadingTrackList.toggleDownload] — shared with AlbumListScreen/ArtistDetailScreen's own album-level download rows, and PlaylistListScreen's playlist ones. */
    suspend fun toggleAlbumDownload(lightContext: SealedLightContext): TrackListDownloadState =
        selfLoadingTracks.toggleDownload(lightContext, downloadRepository)

    // Same pattern as FavoritesScreen's identical wrappers — the View shouldn't
    // reach past this ViewModel to AppGraph's syncQueueRepository directly.
    suspend fun setAlbumFavorite(id: String, favorite: Boolean) = syncQueueRepository.setAlbumFavorite(id, favorite)
    suspend fun setTrackFavorite(id: String, favorite: Boolean) = syncQueueRepository.setTrackFavorite(id, favorite)

    // See ScrollPosition.kt — this ViewModel is the one thing that survives a navigate-away/goBack() round trip.
    val scrollPosition = ScrollPosition()
}

/**
 * `activity` is retained as a property (same reasoning as PlayerScreen's
 * `sealedActivity`) because the per-track play action needs it for
 * `playbackRepository(...)` from inside `Content()`.
 */
class AlbumDetailScreen(
    private val activity: SealedLightActivity,
    private val albumId: String,
    private val initialAlbum: Album? = null,
) : LightScreen<Unit, AlbumDetailScreenViewModel>(activity) {

    override val viewModelClass = AlbumDetailScreenViewModel::class.java

    override fun createViewModel(): AlbumDetailScreenViewModel {
        val graph = AppGraph.from(lightContext)
        return AlbumDetailScreenViewModel(graph.libraryRepository, graph.downloadRepository, graph.syncQueueRepository, albumId, initialAlbum)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val album by viewModel.album.collectAsState()
        val albumDownloadState by viewModel.albumDownloadState.collectAsState()
        val title = album?.name ?: tracks.firstOrNull()?.albumName ?: "Album"
        // Reported live: favoriting an album showed no indication anywhere on
        // this screen. LightTopBarCenter only supports plain text (no
        // icon-in-title slot — checked the SDK directly), so this prefixes a
        // real star character rather than duplicating the title as its own
        // body row just to attach a LightIcon; [title] itself (star-free)
        // still feeds the actions menu's subtitle below, which shouldn't
        // repeat state that's already the option being offered there.
        val topBarTitle = if (album?.isFavorite == true) "★ $title" else title

        // Plain back + centered title now — the 3 album-level action icons that used to
        // live here (favorite, download, add-to-queue, added earlier this session) moved
        // to a long-press on the artwork below instead (issue #16). See the long-press
        // handler's own comment for why the artwork rather than the title, and why a
        // long-press at all rather than leaving them in the top bar.
        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(topBarTitle),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onSleepTimerClick = { navigateTo(::SleepTimerPickerScreen) },
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AlbumArt(
                    lightContext = lightContext,
                    url = album?.coverArtUrl,
                    size = 9f.gridUnitsAsDp(),
                    placeholderIconSize = 4f,
                    modifier = Modifier
                        .padding(vertical = 1f.gridUnitsAsDp())
                        // Long-press opens the album-level actions menu (favorite, download,
                        // add to queue — the 3 icons that used to sit in the top bar). Chose
                        // the artwork over the title as the long-press target: it's the
                        // biggest, most obviously "this is the thing this screen is about"
                        // element on the screen, matches the long-press-a-photo/icon pattern
                        // this device otherwise has no equivalent of, and — unlike the title —
                        // it isn't text that already has its own reason to exist as plain
                        // copy. Tap is a deliberate no-op: the artwork never had tap behavior
                        // of its own before this change, and this doesn't add one.
                        .lightCombinedClickable(
                            onClick = {},
                            onLongClick = {
                                navigateTo({ a ->
                                    val isFavorite = album?.isFavorite == true
                                    val addAlbumToQueueItem = addToQueueActionItem("Add album to queue") {
                                        playbackRepository(activity, lightContext).addToQueue(tracks)
                                    }
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = title,
                                        items = listOf(
                                            favoriteActionItem(isFavorite) { favorite ->
                                                viewModel.setAlbumFavorite(albumId, favorite)
                                            },
                                            trackListDownloadActionItem("album", albumDownloadState) { viewModel.toggleAlbumDownload(lightContext) }.copy(
                                                liveUpdates = viewModel.albumDownloadState.map { s ->
                                                    trackListDownloadActionItem("album", s) { viewModel.toggleAlbumDownload(lightContext) }
                                                },
                                            ),
                                            addAlbumToQueueItem,
                                        ),
                                    )
                                })
                            },
                        ),
                )
            }

            // Inside, not the default Outside — Outside computes its gutter width
            // from listState.layoutInfo, which isn't valid until after the first
            // layout pass, so trailing per-row content (the favorite/download
            // glyphs below) briefly rendered flush against the far edge and then
            // visibly jumped left once the gutter appeared. Reported live.
            // Inside's LazyColumn is always full-width (its scrollbar draws as an
            // overlay, not a reserved gutter), so there's nothing to reflow — but
            // its overlay track would then sit on top of trailing row content
            // instead, which is exactly what got Inside reverted for QueueScreen's
            // own trailing icon earlier. TrackRow below reserves that same width
            // itself as fixed end padding, unconditionally, so there's no
            // dynamically-appearing gutter to glitch *and* no overlap either.
            val listState = rememberPersistedLazyListState(viewModel.scrollPosition)
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                listState = listState,
                uniformItemHeightGridUnits = 3f,
            ) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                    val status by statusFlow.collectAsState(initial = null)
                    TrackRow(
                        track = track,
                        downloadStatus = status?.status,
                        onPlay = {
                            // playAsync() updates title/art synchronously and
                            // continues loading on PlaybackRepository's own scope,
                            // so navigating away immediately after is safe — see
                            // PlaybackRepository.playAsync's doc.
                            val playback = playbackRepository(activity, lightContext)
                            playback.playAsync(tracks, index, albumArtUrl = album?.coverArtUrl)
                            navigateTo(::PlayerScreen)
                        },
                        onOpenActions = {
                            navigateTo({ a ->
                                val addTrackToQueueItem = addToQueueActionItem("Add to queue") {
                                    playbackRepository(activity, lightContext).addToQueue(listOf(track))
                                }
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = track.title,
                                    items = listOf(
                                        favoriteActionItem(track.isFavorite) { favorite ->
                                            viewModel.setTrackFavorite(track.id, favorite)
                                        },
                                        addTrackToQueueItem,
                                        ActionMenuItem(
                                            icon = LightIcons.LIST,
                                            label = "Add to playlist",
                                            onSelect = ActionMenuSelection.Navigate {
                                                navigateTo({ a2 -> PlaylistPickerScreen(a2, track.id) })
                                            },
                                        ),
                                        trackDownloadActionItem(status?.status) { newStatus ->
                                            viewModel.toggleDownload(lightContext, track, newStatus)
                                        }.copy(
                                            liveUpdates = viewModel.downloadStatus(track.id).map { entity ->
                                                trackDownloadActionItem(entity?.status) { newStatus ->
                                                    viewModel.toggleDownload(lightContext, track, newStatus)
                                                }
                                            },
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

/**
 * Tap to play (unchanged); long-press for the full action menu (favorite,
 * queue, playlist, download — issue #16). The actions themselves moved
 * behind that long-press, but their *state* stays visible at a glance as
 * small trailing glyphs — reported live: moving favorite/download to the
 * long-press menu also silently removed any way to tell a track was already
 * favorited or downloaded without opening that menu. Each glyph only
 * renders when it has something to say (favorited, or any known download
 * history) — never a default/empty-state icon, same reasoning as the
 * artwork-off case elsewhere in this app: an icon that's always there reads
 * as chrome, not information.
 */
@Composable
private fun TrackRow(
    track: Track,
    downloadStatus: DownloadStatus?,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onPlay, onLongClick = onOpenActions)
            .padding(
                top = 0.5f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
                start = 1f.gridUnitsAsDp(),
                // Matches the SDK's own SCROLLBAR_WIDTH_UNITS (2f) — the Inside
                // scrollbar draws as an overlay in exactly that much space at the
                // far right, so this keeps the trailing glyphs clear of it
                // unconditionally, rather than only once a list happens to be
                // long enough to actually show a scrollbar (which is exactly the
                // "not known until after first layout" timing this is working
                // around — see the LightLazyScrollView call site's own doc).
                end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.isFavorite) {
            LightIcon(
                icon = LightIcons.STAR,
                size = 1.2f,
                contentDescription = "Favorited",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
        if (downloadStatus != null) {
            LightIcon(
                icon = downloadStatusIcon(downloadStatus),
                size = 1.2f,
                contentDescription = downloadStatusLabel(downloadStatus),
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
