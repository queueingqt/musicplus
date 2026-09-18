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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadEntity
import com.musicplus.app.data.DownloadRepository
import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
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
import kotlinx.coroutines.flow.first
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

    val tracks: StateFlow<List<Track>> = libraryRepository.observeTracksByAlbum(albumId)
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

    // Drives the album-level download action's icon/label: NONE (nothing downloaded),
    // SOME (a mix, all quiet/settled — shows as the "start" icon, tapping downloads
    // the rest), IN_PROGRESS (at least one track actively queued/downloading right
    // now), ALL (every track downloaded — shows as complete, tapping removes all).
    // IN_PROGRESS is checked before SOME/ALL are even considered — reported live:
    // tapping "Download album" instantly showed "Downloaded — remove" because the
    // original 3-state version only ever looked at COMPLETE counts, so "just
    // enqueued, zero actually done yet" and "fully downloaded" were indistinguishable.
    val albumDownloadState: StateFlow<AlbumDownloadState> =
        combine(tracks, downloadRepository.observeAll()) { trackList, downloads ->
            if (trackList.isEmpty()) return@combine AlbumDownloadState.NONE
            val statusById = downloads.associateBy { it.songId }
            val completeCount = trackList.count { statusById[it.id]?.status == DownloadStatus.COMPLETE }
            val anyInProgress = trackList.any { statusById[it.id]?.status in IN_PROGRESS_STATUSES }
            when {
                anyInProgress -> AlbumDownloadState.IN_PROGRESS
                completeCount == trackList.size -> AlbumDownloadState.ALL
                completeCount > 0 -> AlbumDownloadState.SOME
                else -> AlbumDownloadState.NONE
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumDownloadState.NONE)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { libraryRepository.refreshAlbumDetail(albumId) }
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

    /**
     * ALL/IN_PROGRESS -> cancel every track's download (matches
     * [toggleDownload]'s own "cancel doubles as remove" semantics); NONE/SOME
     * -> download whatever isn't already COMPLETE (previously enqueued every
     * track unconditionally, silently re-downloading already-complete ones —
     * the doc here already said "whatever isn't already complete," the code
     * just didn't do that). Suspend, same reasoning as [toggleDownload]: the
     * returned state is what just started, not a guess at what will
     * eventually finish, so the action menu row can show real "in progress"
     * feedback instead of claiming instant completion.
     */
    suspend fun toggleAlbumDownload(lightContext: SealedLightContext): AlbumDownloadState {
        val currentTracks = tracks.value
        return when (albumDownloadState.value) {
            AlbumDownloadState.ALL, AlbumDownloadState.IN_PROGRESS -> {
                currentTracks.forEach { downloadRepository.cancel(lightContext, it.id) }
                AlbumDownloadState.NONE
            }
            AlbumDownloadState.NONE, AlbumDownloadState.SOME -> {
                val completeIds = downloadRepository.observeAll().first()
                    .filter { it.status == DownloadStatus.COMPLETE }
                    .map { it.songId }
                    .toSet()
                currentTracks.filter { it.id !in completeIds }.forEach { downloadRepository.enqueue(lightContext, it) }
                AlbumDownloadState.IN_PROGRESS
            }
        }
    }
}

enum class AlbumDownloadState { NONE, SOME, IN_PROGRESS, ALL }

private val IN_PROGRESS_STATUSES = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)

/**
 * `activity` is retained as a property (same reasoning as PlayerScreen's
 * `sealedActivity`) because the per-track play action needs it for
 * `PlaybackRepositoryHolder.get(...)` from inside `Content()`.
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
        val scope = rememberCoroutineScope()
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

        fun albumDownloadActionItem(state: AlbumDownloadState): ActionMenuItem = ActionMenuItem(
            key = "download",
            icon = when (state) {
                AlbumDownloadState.ALL -> LightIcons.DOWNLOADED_ARROW
                AlbumDownloadState.IN_PROGRESS -> LightIcons.REFRESH
                AlbumDownloadState.SOME, AlbumDownloadState.NONE -> LightIcons.DOWNLOAD_ARROW
            },
            label = when (state) {
                AlbumDownloadState.ALL -> "Downloaded — remove"
                AlbumDownloadState.IN_PROGRESS -> "Downloading — tap to cancel"
                AlbumDownloadState.SOME -> "Some tracks downloaded — download the rest"
                AlbumDownloadState.NONE -> "Download album"
            },
            onSelect = ActionMenuSelection.Perform {
                albumDownloadActionItem(viewModel.toggleAlbumDownload(lightContext))
            },
        )

        fun trackDownloadActionItem(track: Track, status: DownloadStatus?): ActionMenuItem = ActionMenuItem(
            key = "download",
            icon = downloadIcon(status),
            label = downloadStatusLabel(status),
            onSelect = ActionMenuSelection.Perform {
                trackDownloadActionItem(track, viewModel.toggleDownload(lightContext, track, status))
            },
        )

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
            onQueueClick = { navigateTo(::QueueScreen) },
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
                                    lateinit var addAlbumToQueueItem: ActionMenuItem
                                    addAlbumToQueueItem = ActionMenuItem(
                                        icon = LightIcons.ADD,
                                        label = "Add album to queue",
                                        onSelect = ActionMenuSelection.Perform {
                                            val graph = AppGraph.from(lightContext)
                                            PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).addToQueue(tracks)
                                            addAlbumToQueueItem
                                        },
                                    )
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = title,
                                        items = listOf(
                                            favoriteActionItem(isFavorite) { favorite ->
                                                AppGraph.from(lightContext).syncQueueRepository.setAlbumFavorite(albumId, favorite)
                                            },
                                            albumDownloadActionItem(albumDownloadState).copy(
                                                liveUpdates = viewModel.albumDownloadState.map { albumDownloadActionItem(it) },
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
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                uniformItemHeightGridUnits = 3f,
            ) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    val statusFlow = remember(track.id) { viewModel.downloadStatus(track.id) }
                    val status by statusFlow.collectAsState(initial = null)
                    TrackRow(
                        track = track,
                        downloadStatus = status?.status,
                        onPlay = {
                            scope.launch {
                                val graph = AppGraph.from(lightContext)
                                PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir)
                                    .play(tracks, index, albumArtUrl = album?.coverArtUrl)
                                navigateTo(::PlayerScreen)
                            }
                        },
                        onOpenActions = {
                            navigateTo({ a ->
                                lateinit var addTrackToQueueItem: ActionMenuItem
                                addTrackToQueueItem = ActionMenuItem(
                                    icon = LightIcons.ADD,
                                    label = "Add to queue",
                                    onSelect = ActionMenuSelection.Perform {
                                        val graph = AppGraph.from(lightContext)
                                        PlaybackRepositoryHolder.get(activity, graph.apiHolder, lightContext.filesDir).addToQueue(listOf(track))
                                        addTrackToQueueItem
                                    },
                                )
                                ActionsMenuScreen(
                                    activity = a,
                                    subtitle = track.title,
                                    items = listOf(
                                        favoriteActionItem(track.isFavorite) { favorite ->
                                            AppGraph.from(lightContext).syncQueueRepository.setTrackFavorite(track.id, favorite)
                                        },
                                        addTrackToQueueItem,
                                        ActionMenuItem(
                                            icon = LightIcons.LIST,
                                            label = "Add to playlist",
                                            onSelect = ActionMenuSelection.Navigate {
                                                navigateTo({ a2 -> PlaylistPickerScreen(a2, track.id) })
                                            },
                                        ),
                                        trackDownloadActionItem(track, status?.status).copy(
                                            liveUpdates = viewModel.downloadStatus(track.id).map { trackDownloadActionItem(track, it?.status) },
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
                end = 2f.gridUnitsAsDp(),
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
                icon = downloadIcon(downloadStatus),
                size = 1.2f,
                contentDescription = downloadStatusLabel(downloadStatus),
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/**
 * Three real visual states, not two: QUEUED/DOWNLOADING now render distinctly from
 * both "not downloaded" and "downloaded" instead of only toggling between
 * DOWNLOAD_ARROW/DOWNLOADED_ARROW. Uses REFRESH for "in progress" — LOOP was tried
 * first but is the exact same icon the Now Playing screen uses for Repeat, which
 * on-device looked like a stray repeat toggle appearing on tracks whenever an
 * album download was running. There's no dedicated spinner/progress icon in
 * LightIcons; REFRESH isn't used anywhere else in this app, so it doesn't collide.
 */
private fun downloadIcon(status: DownloadStatus?) = when (status) {
    DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.REFRESH
    DownloadStatus.FAILED, null -> LightIcons.DOWNLOAD_ARROW
}

/** Tap semantics: QUEUED/DOWNLOADING/COMPLETE -> stop or remove; FAILED/null -> start. See `toggleDownload`. */
private fun downloadStatusLabel(status: DownloadStatus?): String = when (status) {
    null -> "Download"
    DownloadStatus.QUEUED -> "Queued — tap to cancel"
    DownloadStatus.DOWNLOADING -> "Downloading — tap to cancel"
    DownloadStatus.COMPLETE -> "Downloaded — tap to remove"
    DownloadStatus.FAILED -> "Failed — tap to retry"
}
