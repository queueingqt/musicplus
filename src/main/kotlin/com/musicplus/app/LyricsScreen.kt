package com.musicplus.app

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.LyricsRepository
import com.musicplus.app.data.PlaybackRepository
import com.musicplus.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val lyricsRepository: LyricsRepository,
) : LightViewModel<Unit>() {

    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.currentSnapshot())

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    fun seekTo(ms: Long) = playback.seekTo(ms)

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
            _lyrics.value = lyricsRepository.getLyrics(trackId)
        }
    }
}

class LyricsScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LyricsScreenViewModel>(sealedActivity) {

    override val viewModelClass = LyricsScreenViewModel::class.java

    override fun createViewModel(): LyricsScreenViewModel {
        val graph = AppGraph.from(lightContext)
        val playback = PlaybackRepositoryHolder.get(sealedActivity, graph, lightContext.filesDir)
        return LyricsScreenViewModel(playback, graph.lyricsRepository)
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
            // goBack(), not navigateTo(::PlayerScreen) — this screen is only
            // ever reached by navigating here FROM PlayerScreen (its own
            // microphone icon, the one navigateTo(::LyricsScreen) call site
            // in the app), so the screen directly underneath on the stack is
            // already a PlayerScreen. Pushing a second one on tap meant
            // repeatedly bouncing between Now Playing and Lyrics built up a
            // stack of duplicates — tapping back N times didn't leave Now
            // Playing until every duplicate pair had been popped. Reported
            // live, 2026-09-18.
            onMiniPlayerClick = { goBack() },
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
                            onLineClick = { ms -> viewModel.seekTo(ms) },
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
 *
 * Plain Compose `LazyColumn`, not `LightLazyScrollView` — that component's own
 * scroll-position math (`itemHeightPx` etc., see `LightScrollView.kt`) assumes
 * every row has the same fixed height, which conflicts with letting a long
 * lyric line wrap to more than one line instead of truncating. Reported live:
 * most lines were getting cut off with an ellipsis on a screen that has
 * plenty of room. Losing the SDK's own scrollbar visual here is an acceptable
 * tradeoff — this list is mostly watched auto-scroll, not manually navigated.
 */
@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    positionMs: Long,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        LightText(text = "No lyrics for this track", variant = LightTextVariant.Fine, modifier = modifier)
        return
    }

    val listState = rememberLazyListState()
    val currentIndex = remember(lines, positionMs) {
        lines.indexOfLast { line -> (line.startMs ?: Long.MAX_VALUE) <= positionMs }.coerceAtLeast(0)
    }

    // Auto-scroll follows the current line by default, but a manual drag
    // means the person wants to read something else, not get yanked back to
    // "now" on the very next line change. Reported live. Resumes on its own
    // a few seconds after they let go, rather than requiring them to
    // explicitly ask for it back.
    //
    // One timestamp ("don't auto-scroll again before this instant") is the
    // single source of truth, not two separately-toggled booleans/effects —
    // an earlier version used a `followCurrentLine` flag flipped by one
    // effect and read by another, and during a long musical interlude
    // (current line unchanged for a while) it would resume to the current
    // line, then inexplicably jump back to the old manually-scrolled
    // position, then jump to the current line again once real lyrics
    // resumed — reported live. Two independently-racing effects updating
    // shared state is exactly the shape of bug that produces that kind of
    // "goes back and forth on its own" symptom. A single deterministic timer
    // that every scroll decision reads doesn't have that race.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var resumeAtMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isDragged) {
        resumeAtMs = if (isDragged) Long.MAX_VALUE else System.currentTimeMillis() + 3_000
    }

    LaunchedEffect(currentIndex, resumeAtMs) {
        val waitMs = resumeAtMs - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
        // Re-check rather than trust the wait alone: if a fresh drag started
        // and finished again while this was waiting, resumeAtMs will have
        // moved further out, and this same effect will already be restarting
        // for that reason — this just avoids an extra scroll from the old
        // invocation winning a race against its own restart.
        if (System.currentTimeMillis() >= resumeAtMs) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(modifier = modifier, state = listState) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            LightText(
                // A blank line is real data (a timed pause in the lyrics, e.g. an
                // instrumental break) — kept as its own row rather than collapsed,
                // so the sync timing around it still reads correctly. Some
                // sources mark a pause with their own text (e.g. a literal "♪"),
                // but not all do — an actually-empty line rendered as a plain
                // space was invisible, giving no indication anything was even
                // there while auto-scroll sat on it. Reported live.
                text = line.text.ifBlank { "…" },
                variant = LightTextVariant.Copy,
                lighten = index != currentIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    // Tap a line to jump playback there — every line here has a
                    // real timestamp (this is the Synced state specifically),
                    // but startMs is still nullable on the shared LyricLine type,
                    // so skip the seek rather than jump to 0 on a stray null.
                    .let { m -> line.startMs?.let { ms -> m.lightClickable { onLineClick(ms) } } ?: m }
                    .padding(vertical = 0.35f.gridUnitsAsDp()),
            )
        }
    }
}
