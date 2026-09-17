package com.lightwave.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.PlaybackRepository
import com.lightwave.app.data.PlaybackRepositoryHolder
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerScreenViewModel(
    private val playback: PlaybackRepository,
    private val libraryRepository: com.lightwave.app.data.LibraryRepository,
) : LightViewModel<Unit>() {

    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackState())

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
}

/**
 * Now-playing screen: current track, transport controls, favorite/shuffle/repeat
 * toggles. Reached from HomeScreen's "now playing" row or from a track/album list
 * that starts playback (see AlbumDetailScreen).
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
        val playback = PlaybackRepositoryHolder.get(sealedActivity, graph.apiHolder)
        return PlayerScreenViewModel(playback, graph.libraryRepository)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val track = state.currentTrack

        LightwaveTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), center = LightTopBarCenter.Text("Now Playing"))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(text = track?.title ?: "Nothing playing", variant = LightTextVariant.Heading)
                LightText(text = track?.artistName.orEmpty(), variant = LightTextVariant.Detail)

                LightProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                )
                LightText(
                    text = "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    variant = LightTextVariant.Fine,
                )

                Row(modifier = Modifier.lightClickable { viewModel.toggleShuffle() }) {
                    LightIcon(icon = LightIcons.SHUFFLE, size = 1.5f)
                    LightText(
                        text = if (state.shuffle) "Shuffle: on" else "Shuffle: off",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                    )
                }
                Row(modifier = Modifier.lightClickable { viewModel.cycleRepeatMode() }) {
                    LightIcon(icon = LightIcons.LOOP, size = 1.5f)
                    LightText(
                        text = "Repeat: ${state.repeatMode.name.lowercase()}",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                    )
                }
                Row(modifier = Modifier.lightClickable { viewModel.toggleFavoriteCurrentTrack() }) {
                    LightIcon(icon = if (track?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE, size = 1.5f)
                    LightText(
                        text = if (track?.isFavorite == true) "Favorited" else "Favorite",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                    )
                }
            }

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
