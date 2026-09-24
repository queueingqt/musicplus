package com.musicplus.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.AppLibraryCache
import com.musicplus.app.data.LibraryRepository
import com.musicplus.app.data.PlaybackRepository
import com.musicplus.app.data.playbackRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class AlbumArtScreenViewModel(
    private val playback: PlaybackRepository,
    private val libraryRepository: LibraryRepository,
) : LightViewModel<Unit>() {

    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.currentSnapshot())

    // Tracks the *current* track's art live via the same resolveAlbumArtId
    // priority order PlayerScreenViewModel uses, rather than freezing on
    // whatever was playing the moment this screen opened — skipping/advancing
    // while the full-screen view is up should update it in place, the same
    // way PlayerScreen's own small thumbnail already does.
    // The first value comes from AppLibraryCache.albums, the in-memory copy of the albums list, not from
    // an empty list: this view model is created fresh on every open, and without an album to look up the
    // first frame had no URL at all, so the placeholder showed until the live albums flow below emitted
    // (about 450 ms measured). The album is the same one Now Playing is showing, so its art is already
    // in memory (issue #54).
    val albumArtId: StateFlow<String?> = combine(
        state, libraryRepository.observeAlbums(), playback.albumArtHint,
    ) { s, albums, hint ->
        resolveAlbumArtId(s.currentTrack, albums, hint)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        resolveAlbumArtId(playback.currentSnapshot().currentTrack, AppLibraryCache.albums.value.value, playback.albumArtHint.value),
    )
}

/**
 * Full-screen album art for the current track (issue #37), reached from Now
 * Playing's own art thumbnail tap. A dedicated screen rather than an
 * in-place expansion of the thumbnail — PlayerScreen's layout (title, artist,
 * progress, the icon row, Up Next, transport controls) has no room to grow
 * the art within it without pushing something else off-screen, the same
 * constraint LyricsScreen's doc comment describes for lyrics.
 */
class AlbumArtScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AlbumArtScreenViewModel>(sealedActivity) {

    override val viewModelClass = AlbumArtScreenViewModel::class.java

    override fun createViewModel(): AlbumArtScreenViewModel {
        val graph = AppGraph.from(lightContext)
        val playback = playbackRepository(sealedActivity, lightContext)
        return AlbumArtScreenViewModel(playback, graph.libraryRepository)
    }

    @Composable
    override fun Content() {
        val albumArtId by viewModel.albumArtId.collectAsState()
        val state by viewModel.state.collectAsState()
        val track = state.currentTrack

        // showMiniPlayer = false (default true otherwise) — the point of this
        // screen is showing the art as large as the screen reasonably allows;
        // a mini-player row at the bottom would just eat into that space for
        // a control that's a single tap of the top-bar back button away.
        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(track?.title ?: "Album Art"),
                )
            },
            showMiniPlayer = false,
            // goBack(), not navigateTo(::PlayerScreen) — same reasoning as
            // LyricsScreen's own doc comment: this screen is only ever reached
            // by navigating here FROM PlayerScreen (its own album art tap, the
            // one navigateTo(::AlbumArtScreen) call site in the app), so the
            // screen directly underneath on the stack is already a
            // PlayerScreen. LyricsScreen made the navigateTo(::PlayerScreen)
            // mistake here first and it built up duplicate PlayerScreen
            // instances on repeated round trips — fixed live, 2026-09-18 —
            // so this screen follows the corrected pattern from the start.
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AlbumArt(
                    lightContext = lightContext,
                    coverArtId = albumArtId,
                    // 25 of the device's 27-grid-unit screen width (LightGrid.WIDTH)
                    // — as large as it can be while still leaving a small margin,
                    // matching every other screen's own horizontal padding rather
                    // than running the art edge-to-edge. Width-bound rather than
                    // height-bound: the screen is taller (31 units) than it is
                    // wide, and this is a square image.
                    size = 25f.gridUnitsAsDp(),
                    placeholderIconSize = 8f,
                )
            }
        }
    }
}
