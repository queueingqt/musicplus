package com.musicplus.app.data.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer state, ephemeral and in memory only, by design: it resets to `null` on every app restart.
 *
 * [Countdown.totalMs] is the duration originally selected, carried alongside [Countdown.remainingMs] (which ticks down every
 * second) so the picker can tell which preset or custom row is active: comparing against [remainingMs] would only ever match for
 * the first second.
 */
sealed class SleepTimerState {
    data class Countdown(val remainingMs: Long, val totalMs: Long) : SleepTimerState()
    data object EndOfTrack : SleepTimerState()
}

/**
 * Pauses playback after a while or at the end of the current track. It lives beside the player rather than in any screen for the
 * same reason the player does: it is tied to the detached audio service that keeps playback alive across backgrounding, so it
 * counts down exactly as reliably as playback itself, with no WorkManager or AlarmManager.
 *
 * Two modes that genuinely differ. A countdown has a fixed duration, so it ticks on its own job (and keeps counting while paused:
 * "sleep in 30 minutes, playing or not"). End-of-track has nothing to tick: it waits for [TrackEnd] to say the track is ending
 * and calls [fire] (so pausing to answer the door does not burn the timer down).
 *
 * [onSleep] is the timer's only effect: pause, and only if actually playing, so a timer that fires after the person already paused
 * by hand stays paused instead of toggling back into play.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val onSleep: () -> Unit,
    private val tickMs: Long = 1_000L,
) {
    private val _state = MutableStateFlow<SleepTimerState?>(null)
    val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

    private var job: Job? = null

    /** True while the timer is set to "end of current track". */
    val endOfTrackArmed: Boolean get() = _state.value is SleepTimerState.EndOfTrack

    /** Starts (or restarts, replacing whatever was running) a countdown. */
    fun start(minutes: Int) {
        require(minutes > 0) { "minutes must be positive" }
        startFor(minutes * 60_000L)
    }

    internal fun startFor(totalMs: Long) {
        job?.cancel()
        _state.value = SleepTimerState.Countdown(remainingMs = totalMs, totalMs = totalMs)
        job = scope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                val tick = tickMs.coerceAtMost(remaining)
                delay(tick)
                remaining -= tick
                _state.value = SleepTimerState.Countdown(remainingMs = remaining, totalMs = totalMs)
            }
            job = null
            fire()
        }
    }

    fun startAtEndOfTrack() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState.EndOfTrack
    }

    /** Cancels a running timer of either kind; playback itself is untouched. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    /** Time is up: pause, and clear the timer. */
    fun fire() {
        onSleep()
        _state.value = null
    }
}
