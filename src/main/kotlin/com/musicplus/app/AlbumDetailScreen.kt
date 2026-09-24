package com.musicplus.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.playbackRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AlbumDetailScreenViewModel(
    libraryRepository: LibraryRepository,
    private val albumId: String,
    initialAlbum: Album?,
) : ListScreenViewModel() {

    // See SelfLoadingTrackList.kt: the tracks are read through the refresh-then-read guarantee it exists to enforce.
    private val selfLoadingTracks = SelfLoadingTrackList.forAlbum(libraryRepository, albumId)

    val tracks: StateFlow<List<Track>> = selfLoadingTracks.observeTracks().screenState(viewModelScope, emptyList())

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
        .screenState(viewModelScope, initialAlbum)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { selfLoadingTracks.refreshNow() }
    }
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
        return AlbumDetailScreenViewModel(graph.libraryRepository, albumId, initialAlbum)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val album by viewModel.album.collectAsState()
        val trackActions = rememberTrackActions(activity, lightContext)
        val albumActions = rememberAlbumActions(activity, lightContext)
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
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(topBarTitle),
                )
            },
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AlbumArt(
                    lightContext = lightContext,
                    coverArtId = album?.coverArtId,
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
                            onLongClick = { albumActions.openMenu(albumId, title, album?.isFavorite == true) },
                        ),
                )
            }

            ScreenList(viewModel.scrollPosition) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track = track,
                        onPlay = { trackActions.play(tracks, index, albumArtId = album?.coverArtId) },
                        onOpenActions = { trackActions.openMenu(track) },
                    )
                }
            }
        }
    }
}

// TrackRow is now the shared module in TrackRow.kt — tap to play, long-press
// for the full action menu (favorite, queue, playlist, download — issue #16),
// with favorite/download state visible at a glance as trailing glyphs
// (reported live: moving those actions behind long-press also silently
// removed any way to tell a track was already favorited/downloaded).
