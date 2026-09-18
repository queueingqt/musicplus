package com.musicplus.app

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.PlaybackRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.musicplus.app.data.SubsonicApiHolder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Full-screen lyrics for the current track (issue #14), reached from Now
 * Playing's own microphone icon. Not an inline pane on PlayerScreen itself —
 * that screen is already full (art, title, artist, progress, duration, the
 * icon row, Up Next, transport controls), and a lyrics pane squeezed into
 * whatever space was left rendered as a couple of clipped pixels on-device,
 * confirmed live. A dedicated screen gets the whole content area instead.
 */
class LyricsScreenViewModel(
    private val playback: PlaybackRepository,
    private val apiHolder: SubsonicApiHolder,
) : LightViewModel<Unit>() {

    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.currentSnapshot())

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private var lyricsJob: Job? = null

    init {
        // Fetch lyrics whenever the current track actually changes — not on every
        // position tick, which is also what flows through `state`. Keyed on track
        // id rather than watching currentTrack directly so a re-emission of the
        // same track (e.g. queue metadata refresh) doesn't refetch needlessly.
        state.map { it.currentTrack?.id }
            .distinctUntilChanged()
            .onEach { trackId -> loadLyrics(trackId) }
            .launchIn(viewModelScope)
    }

    private fun loadLyrics(trackId: String?) {
        lyricsJob?.cancel()
        if (trackId == null) {
            _lyrics.value = LyricsState.NoLyrics
            return
        }
        _lyrics.value = LyricsState.Loading
        lyricsJob = viewModelScope.launch {
            val api = apiHolder.get()
            _lyrics.value = if (api == null) {
                LyricsState.Error("Not connected to a server")
            } else {
                try {
                    val entries = api.getLyricsBySongId(trackId)
                    // Prefer an explicit "main" entry if the server bothers to tag
                    // one (spec allows translation/pronunciation alongside it);
                    // otherwise take the first synced entry, then just the first
                    // entry with any lines at all. Real probes against this
                    // project's Navidrome only ever returned a single entry with
                    // no `kind` set, so this is defensive rather than exercised.
                    val best = entries.firstOrNull { it.kind == "main" && it.line.isNotEmpty() }
                        ?: entries.firstOrNull { it.synced && it.line.isNotEmpty() }
                        ?: entries.firstOrNull { it.line.isNotEmpty() }
                    when {
                        best == null -> LyricsState.NoLyrics
                        best.synced -> LyricsState.Synced(best.line.map { LyricLine(it.start, it.value) })
                        else -> LyricsState.Plain(best.line.joinToString("\n") { it.value }.trim())
                    }
                } catch (e: Exception) {
                    LyricsState.Error(e.message ?: "Couldn't load lyrics")
                }
            }
        }
    }
}

class LyricsScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LyricsScreenViewModel>(sealedActivity) {

    override val viewModelClass = LyricsScreenViewModel::class.java

    override fun createViewModel(): LyricsScreenViewModel {
        val graph = AppGraph.from(lightContext)
        val playback = PlaybackRepositoryHolder.get(sealedActivity, graph.apiHolder, lightContext.filesDir)
        return LyricsScreenViewModel(playback, graph.apiHolder)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val lyrics by viewModel.lyrics.collectAsState()
        val track = state.currentTrack

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(track?.title ?: "Lyrics"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            // Three real, honestly-distinguished states plus loading/error —
            // never fakes timing data that isn't there:
            //  - Synced: auto-scrolling, current-line-highlighted list, ticked off
            //    positionMs (see SyncedLyricsList) — same mechanism (compare
            //    playback position against parsed line timestamps) used by other
            //    open-source Subsonic/Navidrome clients (confirmed via Feishin's
            //    source, which drives a per-frame tick off playback time against
            //    parsed LRC-style line starts).
            //  - Plain: plain scrollable text, no fabricated sync.
            //  - NoLyrics/Error: a clear, explicit message, never a silently empty pane.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                when (lyrics) {
                    is LyricsState.Loading ->
                        LightText(text = "Loading lyrics…", variant = LightTextVariant.Fine)

                    is LyricsState.NoLyrics ->
                        LightText(text = "No lyrics for this track", variant = LightTextVariant.Fine)

                    is LyricsState.Error ->
                        LightText(text = "Couldn't load lyrics: ${(lyrics as LyricsState.Error).message}", variant = LightTextVariant.Fine)

                    is LyricsState.Plain ->
                        LightScrollView(modifier = Modifier.fillMaxWidth()) {
                            LightText(text = (lyrics as LyricsState.Plain).text, variant = LightTextVariant.Paragraph)
                        }

                    is LyricsState.Synced ->
                        SyncedLyricsList(
                            lines = (lyrics as LyricsState.Synced).lines,
                            positionMs = state.positionMs,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        }
    }
}

/**
 * Auto-scrolling synced lyrics. The "current line" is simply the last line whose
 * timestamp has passed — a plain linear scan (line counts here are a couple of
 * dozen to low hundreds, nowhere near enough to need a binary search) recomputed
 * whenever [positionMs] or the line list changes; [LaunchedEffect] then animates
 * the shared list scroll state to keep the current line in view, a couple of
 * lines below the top for a little lead-in context.
 */
@Composable
private fun SyncedLyricsList(lines: List<LyricLine>, positionMs: Long, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        LightText(text = "No lyrics for this track", variant = LightTextVariant.Fine, modifier = modifier)
        return
    }

    val listState = rememberLazyListState()
    val currentIndex = remember(lines, positionMs) {
        lines.indexOfLast { line -> (line.startMs ?: Long.MAX_VALUE) <= positionMs }.coerceAtLeast(0)
    }

    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
    }

    LightLazyScrollView(modifier = modifier, listState = listState, uniformItemHeightGridUnits = 2.5f) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            LightText(
                // A blank line is real data (a timed pause in the lyrics, e.g. an
                // instrumental break) — kept as a blank row rather than collapsed,
                // so the sync timing around it still reads correctly.
                text = line.text.ifBlank { " " },
                variant = LightTextVariant.Copy,
                lighten = index != currentIndex,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.35f.gridUnitsAsDp()),
            )
        }
    }
}
