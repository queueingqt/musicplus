package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueScreenViewModel(private val playback: PlaybackRepository) : LightViewModel<Unit>() {
    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.currentSnapshot())

    fun removeFromQueue(index: Int) = viewModelScope.launch { playback.removeFromQueue(index) }
    fun moveQueueItemUp(index: Int) = viewModelScope.launch { playback.moveQueueItem(index, -1) }
    fun moveQueueItemDown(index: Int) = viewModelScope.launch { playback.moveQueueItem(index, 1) }
}

/**
 * Full play queue as its own screen (issue #18) — reachable from Now Playing's
 * top bar and from Home's main menu, independent of Now Playing. Shows the
 * *entire* queue, not just upcoming tracks, with the currently playing one
 * marked so there's always a clear "where am I" anchor — Now Playing's own
 * inline "Up next" preview is intentionally capped to 2 items (it kept getting
 * squeezed off-screen by the header above it), so this is genuinely the only
 * place to see and manage the full list, not a duplicate.
 */
class QueueScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, QueueScreenViewModel>(sealedActivity) {

    override val viewModelClass = QueueScreenViewModel::class.java

    override fun createViewModel(): QueueScreenViewModel {
        val graph = AppGraph.from(lightContext)
        val playback = PlaybackRepositoryHolder.get(sealedActivity, graph.apiHolder, lightContext.filesDir)
        return QueueScreenViewModel(playback)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Queue"),
                )
            },
            // false — this screen already has its own full queue list on
            // screen; a mini-player row would just duplicate it, and its queue
            // icon would loop back to this exact screen (reported live).
            showMiniPlayer = false,
        ) {
            if (state.queue.isEmpty()) {
                LightText(
                    text = "Nothing queued",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                )
            } else {
                // Default (Outside) scrollbar position, not Inside — Inside
                // draws the scrollbar as an overlay directly on top of row
                // content instead of reserving its own space, which put the
                // trailing X right underneath/crossing through the scrollbar
                // track. Reported live as worse, not better. Outside's own
                // reserved gutter is normal spacing before a scrollbar, not a
                // bug — reverted.
                LightLazyScrollView(
                    modifier = Modifier.fillMaxWidth(),
                    uniformItemHeightGridUnits = 3f,
                ) {
                    itemsIndexed(state.queue, key = { _, track -> track.id }) { i, track ->
                        val isCurrent = i == state.currentIndex
                        val upcomingIndex = i - state.currentIndex - 1
                        QueueScreenRow(
                            track = track,
                            isCurrent = isCurrent,
                            // Reorder/remove only make sense for upcoming tracks — same
                            // rule PlaybackRepository itself enforces (removeFromQueue/
                            // moveQueueItem both reject indices at or before current).
                            canMoveUp = !isCurrent && i > state.currentIndex + 1,
                            canMoveDown = !isCurrent && i > state.currentIndex && i < state.queue.lastIndex,
                            canRemove = !isCurrent && i > state.currentIndex,
                            onMoveUp = { viewModel.moveQueueItemUp(i) },
                            onMoveDown = { viewModel.moveQueueItemDown(i) },
                            onRemove = { viewModel.removeFromQueue(i) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueScreenRow(
    track: Track,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrent) {
            LightIcon(
                icon = LightIcons.PLAY,
                size = 1.5f,
                contentDescription = "Now playing",
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        // Reorder icons lead the row, before the title, not trail it — reported
        // live as the requested layout.
        if (canMoveUp) {
            LightIcon(
                icon = LightIcons.UP,
                size = 1.5f,
                contentDescription = "Move up",
                modifier = Modifier
                    .lightClickable(onClick = onMoveUp)
                    .padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        if (canMoveDown) {
            LightIcon(
                icon = LightIcons.DOWN,
                size = 1.5f,
                contentDescription = "Move down",
                modifier = Modifier
                    .lightClickable(onClick = onMoveDown)
                    .padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        LightText(
            text = track.title,
            variant = if (isCurrent) LightTextVariant.Heading else LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canRemove) {
            // CLOSE (X), not TRASH — matches the LightOS convention of a plain
            // X for "remove/dismiss this" rather than a trash-can metaphor.
            LightIcon(
                icon = LightIcons.CLOSE,
                size = 1.5f,
                contentDescription = "Remove from queue",
                modifier = Modifier
                    .lightClickable(onClick = onRemove)
                    .padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
