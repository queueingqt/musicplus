package com.musicplus.app.data.playback

import com.musicplus.app.PlaybackState
import com.musicplus.app.data.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Somewhere a listen is reported. Each sink decides for itself whether a given listen concerns it. */
interface ListenSink {
    suspend fun started(listen: Listen, positionMs: Long) {}
    suspend fun counted(listen: Listen) {}
    suspend fun progress(listen: Listen, positionMs: Long, paused: Boolean) {}
    suspend fun ended(listen: Listen, positionMs: Long) {}

    /** True if this sink wants [progress] calls while [listen] lasts (a server that keeps a resume position). Asked of the server's api, so it can suspend. */
    suspend fun wantsProgress(listen: Listen): Boolean = false
}

/**
 * Turns the playback state into reports to the sinks: [ListenTracker] says when a listen starts, counts and ends, and this
 * sends each event to every sink without ever letting a slow or failing sink touch playback. A sink that wants progress
 * (a Jellyfin server keeping a resume position) gets one every [progressIntervalMs] while its listen is the current one.
 */
class ListenReporting(
    private val scope: CoroutineScope,
    private val sinks: List<ListenSink>,
    private val tracker: ListenTracker = ListenTracker(),
    private val progressIntervalMs: Long = 15_000L,
) {
    private var latest: PlaybackState? = null
    private var ticker: Job? = null

    /** A new play began: the same song heard again is a new listen. */
    fun beginPlay() = tracker.beginPlay()

    fun start(states: Flow<PlaybackState>): Job = scope.launch { states.collect { onState(it) } }

    fun onState(s: PlaybackState) {
        latest = s
        for (event in tracker.observe(s)) when (event) {
            is ListenEvent.Started -> {
                fireAll { started(event.listen, event.positionMs) }
                startTicker(event.listen)
            }
            is ListenEvent.Counted -> fireAll { counted(event.listen) }
            is ListenEvent.Ended -> {
                ticker?.cancel()
                fireAll { ended(event.listen, event.positionMs) }
            }
        }
    }

    private fun startTicker(listen: Listen) {
        ticker?.cancel()
        ticker = scope.launch {
            if (sinks.none { it.wantsProgress(listen) }) return@launch
            while (true) {
                delay(progressIntervalMs)
                val s = latest ?: break
                if (s.currentTrack?.id != listen.trackId) break
                fireAll { if (wantsProgress(listen)) progress(listen, s.positionMs, paused = !s.isPlaying) }
            }
        }
    }

    /** Each call on its own coroutine, so one sink cannot delay another, and an error is logged, never thrown into playback. */
    private fun fireAll(call: suspend ListenSink.() -> Unit) {
        for (sink in sinks) scope.launch {
            try {
                sink.call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ListenReporting", "${sink::class.simpleName} failed to report a listen", e)
            }
        }
    }
}
