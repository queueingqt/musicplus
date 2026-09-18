package com.musicplus.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.PlaybackRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerScreenViewModel(
    private val playback: PlaybackRepository,
    private val libraryRepository: com.musicplus.app.data.LibraryRepository,
) : LightViewModel<Unit>() {

    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.currentSnapshot())

    // Prefers, in order: (1) the explicit hint PlaybackRepository.play() was
    // given — set synchronously by the caller the same moment playback starts,
    // see PlaybackRepository.albumArtUrlHint's doc; (2) the *album's* art
    // looked up by id, for sessions that didn't supply a hint (e.g. resuming
    // via the mini-player, where nothing is "in progress" to pass one); (3)
    // the track's own art as a last resort. Navidrome assigns every individual
    // track its own distinct coverArt id (a "mf-..." id, separate from the
    // album's "al-..." one) even when it's the exact same embedded image every
    // other track on the album shares, so falling all the way back to (3)
    // without ever reaching (1) or (2) means a real, uncached fetch every
    // time — confirmed on-device 2026-09-18. (1) is what actually avoids the
    // one-frame flash a Room-based lookup can't fully avoid on its own, since
    // it needs an async combine to resolve even when the answer is already
    // known synchronously at play()-time.
    //
    // Seeded from the same synchronous values used above, not null — every
    // navigation to PlayerScreen (even replaying the identical track) creates
    // a fresh ViewModel, and this StateFlow's initial value otherwise has
    // nothing to do with whether the art was already known a moment ago.
    // Confirmed on-device 2026-09-18: even the *same* track played twice in a
    // row still flashed placeholder-then-art here, purely from this cold start.
    val albumArtUrl: StateFlow<String?> = combine(
        state, libraryRepository.observeAlbums(), playback.albumArtUrlHint,
    ) { s, albums, hint ->
        val track = s.currentTrack
        hint ?: albums.find { it.id == track?.albumId }?.coverArtUrl ?: track?.coverArtUrl
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        playback.albumArtUrlHint.value ?: playback.currentSnapshot().currentTrack?.coverArtUrl,
    )

    fun togglePlayPause() = playback.togglePlayPause()
    fun skipBack() = playback.skipBack()
    fun skipForward() = playback.skipForward()
    fun skipToPrevious() = playback.skipToPrevious()
    fun skipToNext() = playback.skipToNext()

    fun toggleFavoriteCurrentTrack() {
        val track = state.value.currentTrack ?: return
        viewModelScope.launch { libraryRepository.setTrackFavorite(track.id, !track.isFavorite) }
    }

    fun toggleShuffle() = playback.setShuffle(!state.value.shuffle)

    fun cycleRepeatMode() {
        val next = when (state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.REPEAT_QUEUE
            RepeatMode.REPEAT_QUEUE -> RepeatMode.REPEAT_TRACK
            RepeatMode.REPEAT_TRACK -> RepeatMode.OFF
        }
        playback.setRepeatMode(next)
    }

    fun removeFromQueue(index: Int) = viewModelScope.launch { playback.removeFromQueue(index) }
    fun moveQueueItemUp(index: Int) = viewModelScope.launch { playback.moveQueueItem(index, -1) }
    fun moveQueueItemDown(index: Int) = viewModelScope.launch { playback.moveQueueItem(index, 1) }
}

/**
 * Now-playing screen: current track, transport controls, favorite/shuffle/repeat
 * toggles, and (Issue #4) the upcoming queue with per-row remove and up/down
 * reorder. Reached from HomeScreen's menu, the persistent mini-player
 * (MusicPlusScaffold), or a track/album list that starts playback (see
 * AlbumDetailScreen).
 *
 * `sealedActivity` is captured as a property here (unlike other screens) because
 * `PlaybackRepositoryHolder.get(...)` needs it in `createViewModel()`, and
 * `SimpleLightScreen` doesn't retain the raw activity for subclasses to reuse
 * (only the derived `lightContext` is exposed) — see the SDK reference notes.
 */
class PlayerScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerScreenViewModel>(sealedActivity) {

    override val viewModelClass = PlayerScreenViewModel::class.java

    override fun createViewModel(): PlayerScreenViewModel {
        val graph = AppGraph.from(lightContext)
        val playback = PlaybackRepositoryHolder.get(sealedActivity, graph.apiHolder, lightContext.filesDir)
        return PlayerScreenViewModel(playback, graph.libraryRepository)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val albumArtUrl by viewModel.albumArtUrl.collectAsState()
        val track = state.currentTrack
        val upcoming = state.upcomingTracks

        // showMiniPlayer = false — the full now-playing UI is already on screen
        // here, a mini-player row would just duplicate it.
        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Now Playing"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.LIST,
                        onClick = { navigateTo(::QueueScreen) },
                        contentDescription = "View queue",
                    ),
                )
            },
            showMiniPlayer = false,
            bottomBar = {
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(LightIcons.REWIND, viewModel::skipToPrevious, contentDescription = "Previous track"),
                        LightBarButton.LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, viewModel::skipBack, contentDescription = "Back 15s"),
                        LightBarButton.LightIcon(
                            if (state.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                            viewModel::togglePlayPause,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        ),
                        LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, viewModel::skipForward, contentDescription = "Forward 15s"),
                        LightBarButton.LightIcon(LightIcons.FAST_FORWARD, viewModel::skipToNext, contentDescription = "Next track"),
                    ),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Same size/padding as AlbumDetailScreen's header art — was 13f
                // with its own top-level padding before, which (combined with
                // title/artist/progress/duration/shuffle-row below it) pushed
                // "Up next" off the bottom of the screen entirely on-device,
                // and made the art size visibly jump between these two screens.
                // Confirmed both problems live 2026-09-18.
                AlbumArt(
                    lightContext = lightContext,
                    url = albumArtUrl,
                    size = 9f.gridUnitsAsDp(),
                    placeholderIconSize = 4f,
                    modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = track?.title ?: "Nothing playing",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.fillMaxWidth(),
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = track?.artistName.orEmpty(),
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.fillMaxWidth(),
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (state.errorMessage != null) {
                    LightText(
                        text = "Playback error: ${state.errorMessage}",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                }

                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                LightProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                )
                LightText(
                    text = "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    variant = LightTextVariant.Fine,
                )

                // Icon-only, no text labels: shuffle/repeat/favorite are all standard,
                // self-explanatory iconography — a visible label next to each one
                // defeats the point of using icons at all. contentDescription still
                // carries the meaning for accessibility.
                Row(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp())) {
                    LightIcon(
                        icon = LightIcons.SHUFFLE,
                        size = 1.5f,
                        contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                        modifier = Modifier
                            .lightClickable { viewModel.toggleShuffle() }
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    LightIcon(
                        icon = LightIcons.LOOP,
                        size = 1.5f,
                        contentDescription = "Repeat ${state.repeatMode.name.lowercase()}",
                        modifier = Modifier
                            .lightClickable { viewModel.cycleRepeatMode() }
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    LightIcon(
                        icon = if (track?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        size = 1.5f,
                        contentDescription = if (track?.isFavorite == true) "Favorited" else "Favorite",
                        modifier = Modifier
                            .lightClickable { viewModel.toggleFavoriteCurrentTrack() }
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }
            }

            // "Up Next:" and the next track's name share one row, justified
            // (label hugs the left edge, title hugs the right) rather than
            // clumped left — just a quick glance at what's coming up, not a
            // management surface. Full queue (reorder/remove, the whole list)
            // lives in QueueScreen, reachable via the queue icon on the top bar
            // (this screen) or the mini-player (every other screen) — no
            // separate "view queue" link needed here since that icon is
            // already always visible. Nothing renders at all when the queue is
            // empty — no placeholder text.
            if (upcoming.isNotEmpty()) {
                val nextTrack = upcoming.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { navigateTo(::QueueScreen) }
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LightText(text = "Up Next:", variant = LightTextVariant.Heading)
                    LightText(
                        text = nextTrack.title,
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        align = TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
