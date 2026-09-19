package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.musicplus.app.data.PlaybackRepository
import com.musicplus.app.data.SleepTimerState
import com.musicplus.app.data.playbackRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

/** Fixed quick-pick durations — same role as [STREAM_QUALITY_PRESETS] plays for QualityPickerScreen. */
private val SLEEP_TIMER_PRESET_MINUTES = listOf(15, 30, 45, 60)

/**
 * Reached from Now Playing's alarm-clock icon (see PlayerScreen's icon row) —
 * both to start a timer for the first time and, while one's already running,
 * to change its duration or cancel it outright. Applies straight to
 * [PlaybackRepository] on selection (no result/callback round-trip the way
 * QualityPickerScreen has one, since this picker has exactly one caller and
 * already has direct access to the repository it needs to mutate, unlike
 * QualityPickerScreen which is reused across three different settings its
 * caller owns).
 */
class SleepTimerPickerScreenViewModel(private val playback: PlaybackRepository) : LightViewModel<Unit>() {
    val sleepTimerState: StateFlow<SleepTimerState?> = playback.sleepTimerState

    fun startCountdown(minutes: Int) = playback.startSleepTimer(minutes)
    fun startEndOfTrack() = playback.startSleepTimerAtEndOfTrack()
    fun cancel() = playback.cancelSleepTimer()
}

class SleepTimerPickerScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SleepTimerPickerScreenViewModel>(sealedActivity) {

    override val viewModelClass = SleepTimerPickerScreenViewModel::class.java

    override fun createViewModel(): SleepTimerPickerScreenViewModel {
        val playback = playbackRepository(sealedActivity, lightContext)
        return SleepTimerPickerScreenViewModel(playback)
    }

    @Composable
    override fun Content() {
        val timerState by viewModel.sleepTimerState.collectAsState()
        val countdown = timerState as? SleepTimerState.Countdown

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Sleep Timer"),
                )
            },
            // Reached from Now Playing's own alarm icon — a mini-player row
            // here would just duplicate that same screen one tap away, same
            // reasoning QueueScreen's identical showMiniPlayer = false uses.
            // Reported live, 2026-09-18.
            showMiniPlayer = false,
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                // Live status line, same "second line shows the live value"
                // idea as QualityMenuRow's subtitle — a running countdown's
                // own row can't show this itself the way a persisted
                // setting's current value can, since which row (if any) is
                // "selected" only reflects the *originally chosen* duration
                // (see [SleepTimerState.Countdown.totalMs]'s doc), not how
                // much is actually left.
                when {
                    countdown != null -> LightText(
                        text = "Pausing in ${formatSleepTimerRemaining(countdown.remainingMs)}",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                    timerState is SleepTimerState.EndOfTrack -> LightText(
                        text = "Pausing at the end of this track",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                }

                // Only shown while a timer is actually running — nothing to
                // cancel otherwise. Placed first, ahead of the preset rows,
                // so it's the first thing seen re-opening this screen mid-
                // countdown to check on/change it.
                if (timerState != null) {
                    SleepTimerRow(
                        label = "Cancel timer",
                        selected = false,
                        onClick = { viewModel.cancel(); goBack() },
                    )
                }

                for (preset in SLEEP_TIMER_PRESET_MINUTES) {
                    SleepTimerRow(
                        label = "$preset min",
                        selected = countdown?.totalMs == preset * 60_000L,
                        onClick = { viewModel.startCountdown(preset); goBack() },
                    )
                }

                // Same "a value that isn't null and isn't one of the presets
                // can only have gotten there through this same row" reasoning
                // QualityPickerScreen's isCustom uses, applied to totalMs
                // rather than a live/decaying value — see
                // [SleepTimerState.Countdown.totalMs]'s doc for why that
                // matters here specifically.
                val customMinutes = countdown
                    ?.totalMs
                    ?.let { (it / 60_000L).toInt() }
                    ?.takeIf { it !in SLEEP_TIMER_PRESET_MINUTES }
                SleepTimerRow(
                    label = if (customMinutes != null) "Custom ($customMinutes min)" else "Custom…",
                    selected = customMinutes != null,
                    onClick = {
                        // NumericEntryScreen, not TextEditScreen — see its own
                        // doc for why (opens straight to digits, no QWERTY
                        // default to confuse a minutes-only field). Reported
                        // live, 2026-09-18.
                        navigateTo({ a ->
                            NumericEntryScreen(a, "Sleep timer", customMinutes?.toString().orEmpty(), unitSuffix = "min")
                        }) { result ->
                            result.toIntOrNull()?.takeIf { it > 0 }?.let {
                                viewModel.startCountdown(it)
                                goBack()
                            }
                        }
                    },
                )

                SleepTimerRow(
                    label = "End of current track",
                    selected = timerState is SleepTimerState.EndOfTrack,
                    onClick = { viewModel.startEndOfTrack(); goBack() },
                )
            }
        }
    }
}

// Not private — reused by PlayerScreen's and MusicPlusScaffold's own live
// "MM:SS left" badges on the sleep timer icon (Now Playing's icon row and
// the mini-player), same formatting as this screen's own "Pausing in..." line.
fun formatSleepTimerRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

// Same leading-SELECT_ON-indicator shape as QualityPickerScreen's QualityRow
// — the established "which one of these is current" convention in this app.
@Composable
private fun SleepTimerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            LightIcon(
                icon = LightIcons.SELECT_ON,
                size = 1.5f,
                contentDescription = "Selected",
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}
