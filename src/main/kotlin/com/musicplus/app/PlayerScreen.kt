package com.musicplus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppDisplayPrefs
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
import com.thelightphone.sdk.ui.LightIconConfiguration
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which URL actually counts as "the current album art" — shared by
 * [PlayerScreenViewModel] and `AlbumArtScreenViewModel` so both screens agree
 * on exactly the same art for the same playback state, rather than each
 * re-deriving its own answer.
 *
 * Prefers, in order: (1) the explicit hint PlaybackRepository.play() was
 * given — set synchronously by the caller the same moment playback starts,
 * see PlaybackRepository.albumArtUrlHint's doc; (2) the *album's* art looked
 * up by id, for sessions that didn't supply a hint (e.g. resuming via the
 * mini-player, where nothing is "in progress" to pass one); (3) the track's
 * own art as a last resort. Navidrome assigns every individual track its own
 * distinct coverArt id (a "mf-..." id, separate from the album's "al-..."
 * one) even when it's the exact same embedded image every other track on the
 * album shares, so falling all the way back to (3) without ever reaching (1)
 * or (2) means a real, uncached fetch every time — confirmed on-device
 * 2026-09-18. (1) is what actually avoids the one-frame flash a Room-based
 * lookup can't fully avoid on its own, since it needs an async combine to
 * resolve even when the answer is already known synchronously at
 * play()-time.
 */
fun resolveAlbumArtUrl(track: Track?, albums: List<Album>, hint: String?): String? =
    hint ?: albums.find { it.id == track?.albumId }?.coverArtUrl ?: track?.coverArtUrl

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerScreenViewModel(
    private val playback: PlaybackRepository,
    private val libraryRepository: com.musicplus.app.data.LibraryRepository,
    private val syncQueueRepository: com.musicplus.app.data.SyncQueueRepository,
) : LightViewModel<Unit>() {

    val state: StateFlow<PlaybackState> =
        playback.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.currentSnapshot())

    // Seeded from the same synchronous values [resolveAlbumArtUrl] would
    // eventually settle on, not null — every navigation to PlayerScreen (even
    // replaying the identical track) creates a fresh ViewModel, and this
    // StateFlow's initial value otherwise has nothing to do with whether the
    // art was already known a moment ago. Confirmed on-device 2026-09-18:
    // even the *same* track played twice in a row still flashed
    // placeholder-then-art here, purely from this cold start. The seed skips
    // the album lookup (no album list available synchronously) — harmless,
    // since resolveAlbumArtUrl only reaches that tier when there's no hint,
    // and a hint is exactly what's usually available synchronously anyway.
    val albumArtUrl: StateFlow<String?> = combine(
        state, libraryRepository.observeAlbums(), playback.albumArtUrlHint,
    ) { s, albums, hint ->
        resolveAlbumArtUrl(s.currentTrack, albums, hint)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        resolveAlbumArtUrl(playback.currentSnapshot().currentTrack, emptyList(), playback.albumArtUrlHint.value),
    )

    /** True while the current track's favorite state is still waiting to reach the server (issue #24) — the star's own filled/outline state already reflects the optimistic local value, so this drives a separate "still syncing" indicator rather than a third icon state. */
    val isFavoritePending: StateFlow<Boolean> = state
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .flatMapLatest { trackId -> trackId?.let { syncQueueRepository.isFavoritePending(it) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun togglePlayPause() = playback.togglePlayPause()
    fun skipBack() = playback.skipBack()
    fun skipForward() = playback.skipForward()
    fun skipToPrevious() = playback.skipToPrevious()
    fun skipToNext() = playback.skipToNext()

    fun toggleFavoriteCurrentTrack() {
        val track = state.value.currentTrack ?: return
        val newValue = !track.isFavorite
        // Patches PlaybackRepository's own queue snapshot too, not just Room/the
        // server — state.currentTrack is sourced from that snapshot, which
        // setTrackFavorite alone never touches, so without this the write
        // succeeds but the star icon never visibly updates (issue #8).
        playback.updateTrackFavorite(track.id, newValue)
        viewModelScope.launch { syncQueueRepository.setTrackFavorite(track.id, newValue) }
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
        val playback = PlaybackRepositoryHolder.get(sealedActivity, graph, lightContext.filesDir)
        return PlayerScreenViewModel(playback, graph.libraryRepository, graph.syncQueueRepository)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val albumArtUrl by viewModel.albumArtUrl.collectAsState()
        val isFavoritePending by viewModel.isFavoritePending.collectAsState()
        val showArtwork by AppDisplayPrefs.showAlbumArtwork.collectAsState()
        val track = state.currentTrack
        val upcoming = state.upcomingTracks

        // Self-dismiss rather than show an empty "Nothing playing" screen —
        // there's no legitimate reason to linger here once nothing's queued.
        // PlaybackRepository.clearQueue() now always keeps the currently
        // playing track (reported live: clearing the queue shouldn't stop
        // playback), so this no longer fires from that flow specifically —
        // it's a defensive fallback for the case nothing was ever queued in
        // the first place, kept because reaching this screen with a null
        // track should never show a dead page regardless of how it happens.
        LaunchedEffect(track) {
            if (track == null) goBack()
        }

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
                            // REFRESH during the loading window — see MusicPlusScaffold's
                            // identical mini-player treatment for why this can't just
                            // reuse the plain paused-looking PLAY icon here.
                            when {
                                state.isLoading -> LightIcons.REFRESH
                                state.isPlaying -> LightIcons.PAUSE
                                else -> LightIcons.PLAY
                            },
                            viewModel::togglePlayPause,
                            contentDescription = when {
                                state.isLoading -> "Loading"
                                state.isPlaying -> "Pause"
                                else -> "Play"
                            },
                        ),
                        LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, viewModel::skipForward, contentDescription = "Forward 15s"),
                        LightBarButton.LightIcon(LightIcons.FAST_FORWARD, viewModel::skipToNext, contentDescription = "Next track"),
                    ),
                )
            },
        ) {
            // Reported previously, still not addressed until now: with artwork off,
            // AlbumArt below renders nothing (zero height — see its own doc), so
            // without this the title/timeline/controls block just stacked at the
            // top under an empty top bar instead of sitting in the middle of the
            // space the artwork would have occupied. Only added when artwork's off
            // — the weight(1f) spacer below (which pushes "Up Next" toward the
            // bottom) already gives the with-artwork layout its intended shape, and
            // balancing it with an equal spacer here would re-center that case too,
            // which was never reported as wrong.
            if (!showArtwork) {
                Spacer(modifier = Modifier.weight(1f))
            }
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
                //
                // Only tappable when there's real art to show full-screen (issue
                // #37) — AlbumArt itself already renders just the placeholder
                // icon for a null url or the artwork setting being off, and
                // opening AlbumArtScreen onto that would just be a blank/empty
                // full-screen view with nothing to look at.
                AlbumArt(
                    lightContext = lightContext,
                    url = albumArtUrl,
                    size = 9f.gridUnitsAsDp(),
                    placeholderIconSize = 4f,
                    modifier = Modifier
                        .let { m ->
                            if (showArtwork && albumArtUrl != null) {
                                m.lightClickable { navigateTo(::AlbumArtScreen) }
                            } else {
                                m
                            }
                        }
                        .padding(vertical = 1f.gridUnitsAsDp()),
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
                // LightProgressBar (sdk/ui/.../LightProgressBar.kt) is two plain
                // Boxes with a background color — no semantics{} block at all, so
                // on its own it's fully decorative to a screen reader (confirmed
                // by reading the SDK source, not assumed). The position/duration
                // text right below it already makes this info available, but only
                // as a fixed text node a screen reader has to separately land on;
                // exposing it directly on the bar itself as real progress semantics
                // (rather than leaving it silent) is the standard, low-risk fix —
                // doesn't touch the SDK component, just what this screen wraps it
                // with. Never checked against a screen reader before this pass
                // (issue #36).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = state.positionMs.toFloat(),
                                range = 0f..state.durationMs.toFloat().coerceAtLeast(0f),
                            )
                            contentDescription =
                                "Playback position ${formatDuration(state.positionMs)} of ${formatDuration(state.durationMs)}"
                        },
                ) {
                    LightProgressBar(
                        colors = LightThemeTokens.colors,
                        progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                    )
                }
                LightText(
                    text = "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    variant = LightTextVariant.Fine,
                )

                // Icon-only, no text labels: shuffle/repeat/favorite/lyrics are all
                // standard, self-explanatory iconography — a visible label next to
                // each one defeats the point of using icons at all.
                // contentDescription still carries the meaning for accessibility.
                // CenterVertically, not the Row default (Top) — ToggleableIcon's
                // circular pill adds its own padding around shuffle/repeat, making
                // those two taller than the plain favorite/lyrics LightIcons next
                // to them; Top-aligned, that extra padding pushed their glyphs
                // down relative to the others instead of sharing a center.
                // Reported live.
                Row(
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleableIcon(
                        icon = LightIcons.SHUFFLE,
                        active = state.shuffle,
                        contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                        onClick = { viewModel.toggleShuffle() },
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    ToggleableIcon(
                        icon = LightIcons.LOOP,
                        active = state.repeatMode != RepeatMode.OFF,
                        // "1" badge distinguishes REPEAT_TRACK from REPEAT_QUEUE —
                        // both use the same LOOP glyph and active-pill treatment,
                        // and there's no dedicated "repeat one" icon in LightIcons
                        // (confirmed via source) to tell them apart otherwise.
                        badge = if (state.repeatMode == RepeatMode.REPEAT_TRACK) "1" else null,
                        // Was "Repeat ${state.repeatMode.name.lowercase()}" — for
                        // REPEAT_QUEUE/REPEAT_TRACK that read the raw enum constant
                        // with its underscore intact ("Repeat repeat_queue", "Repeat
                        // repeat_track") to a screen reader instead of words. Purely
                        // an accessibility bug — nothing on screen shows this string,
                        // only TalkBack announcing this icon — and was never checked
                        // against a screen reader before this pass (issue #36).
                        contentDescription = when (state.repeatMode) {
                            RepeatMode.OFF -> "Repeat off"
                            RepeatMode.REPEAT_QUEUE -> "Repeat queue"
                            RepeatMode.REPEAT_TRACK -> "Repeat track"
                        },
                        onClick = { viewModel.cycleRepeatMode() },
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    LightIcon(
                        icon = if (track?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        size = 1.5f,
                        contentDescription = if (track?.isFavorite == true) "Favorited" else "Favorite",
                        modifier = Modifier
                            .lightClickable { viewModel.toggleFavoriteCurrentTrack() }
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    // Opens LyricsScreen (issue #14) — a separate full screen, not
                    // an inline pane here: this screen is already full (art, title,
                    // artist, progress, duration, this icon row, Up Next, transport
                    // controls) and a lyrics pane squeezed into whatever was left
                    // rendered as a couple of clipped pixels on-device, confirmed
                    // live. Only shown once something is actually playing.
                    if (track != null) {
                        LightIcon(
                            icon = LightIcons.AUDIO_MESSAGE,
                            size = 1.5f,
                            contentDescription = "Lyrics",
                            modifier = Modifier
                                .lightClickable { navigateTo(::LyricsScreen) }
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }
                }

                // The star's own filled/outline state already reflects the tap
                // optimistically — this is the only signal that it hasn't
                // actually reached the server yet (issue #24).
                if (isFavoritePending) {
                    LightText(
                        text = "Favorite syncing…",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.fillMaxWidth(),
                        align = TextAlign.Center,
                    )
                }
            }

            // Pushes "Up Next" down to sit just above the transport controls
            // rather than immediately under the shuffle/repeat/favorite row —
            // reported live as reading like part of the now-playing track info
            // instead of a lead-in to the controls below it.
            Spacer(modifier = Modifier.weight(1f))

            // "Up Next:" and the next track's name share one row, centered as
            // a pair rather than justified (label hugging the left edge,
            // title hugging the right) — reported live as reading better
            // centered. Small/Fine text, same size as the duration readout
            // above — this is a secondary hint, not on par with the current
            // track's own title. Full queue (reorder/remove, the whole list)
            // lives in QueueScreen, reachable via the queue icon on the top
            // bar (this screen) or the mini-player (every other screen) — no
            // separate "view queue" link needed here since that icon is
            // already always visible. Nothing renders at all when the queue
            // is empty — no placeholder text.
            if (upcoming.isNotEmpty()) {
                val nextTrack = upcoming.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { navigateTo(::QueueScreen) }
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp(), Alignment.CenterHorizontally),
                ) {
                    LightText(text = "Up Next:", variant = LightTextVariant.Fine, maxLines = 1)
                    LightText(
                        text = nextTrack.title,
                        variant = LightTextVariant.Fine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 14f.gridUnitsAsDp()),
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

/**
 * A shuffle/repeat-style icon that shows whether it's currently active — the
 * plain glyph alone looked identical whether on or off, which is exactly what
 * was reported live: nothing in Now Playing showed shuffle/repeat's current
 * state at all. [active] draws a soft circular pill behind the glyph, same
 * tint MiniPlayerBar already uses for its own background treatment
 * ([LightThemeTokens.colors.contentSecondary]) so this reads as "the app's
 * existing active/highlighted look," not a new one-off style. [badge] (used
 * only for REPEAT_TRACK, to distinguish it from REPEAT_QUEUE) overlays a tiny
 * label in the corner — there's no dedicated "repeat one" icon to reach for
 * instead (confirmed via LightIcons source).
 */
@Composable
private fun ToggleableIcon(
    icon: LightIconConfiguration,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier = modifier
            .background(
                if (active) LightThemeTokens.colors.contentSecondary.copy(alpha = 0.2f) else Color.Transparent,
                CircleShape,
            )
            .lightClickable(onClick = onClick)
            .padding(0.35f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(icon = icon, size = 1.5f, contentDescription = contentDescription)
        if (badge != null) {
            // Micro (8sp) was illegible sitting directly on the icon's own
            // strokes — reported live. Superfine (16sp) plus a small solid
            // backing chip, offset outside the circle instead of centered on
            // it, keeps the badge from blending into the glyph underneath it.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 0.3f.gridUnitsAsDp(), y = 0.3f.gridUnitsAsDp())
                    .background(LightThemeTokens.colors.background, CircleShape)
                    .padding(horizontal = 0.15f.gridUnitsAsDp()),
            ) {
                LightText(text = badge, variant = LightTextVariant.Superfine)
            }
        }
    }
}
