package com.musicplus.app.data.playback

import com.musicplus.app.Track
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real player as the queue sees it, with the quirks the code above it is written around (see [QueuePlayer]): [setMediaQueue]
 * resets the duration and position to 0 at once and the item is only "ready" [readyAfterMs] later, [isPlaying] drops out until then,
 * and [seekTo] clamps to the duration, so a seek made before the duration is known lands at 0.
 */
class FakePlayer(private val scope: CoroutineScope, private val itemDurationMs: Long = 200_000, private val readyAfterMs: Long = 15) : QueuePlayer {
    private val index = MutableStateFlow(0)
    private val playing = MutableStateFlow(false)
    private val position = MutableStateFlow(0L)
    private val duration = MutableStateFlow(0L)
    private val failure = MutableStateFlow<LightAudioError?>(null)

    override val currentMediaItemIndex: StateFlow<Int> get() = index
    override val isPlaying: StateFlow<Boolean> get() = playing
    override val positionMs: StateFlow<Long> get() = position
    override val durationMs: StateFlow<Long> get() = duration
    override val error: StateFlow<LightAudioError?> get() = failure

    /** Every call the queue made, in order, as text: "setMediaQueue(5, 2)", "play", "seekTo(1000)". */
    val calls = CopyOnWriteArrayList<String>()

    /** What the player holds right now, and what every past `setMediaQueue` held. */
    var held: List<LightAudioItem> = emptyList()
        private set
    val heldTitles get() = held.map { it.metadata.title }
    val swaps = CopyOnWriteArrayList<List<String>>()

    var ready = true
    private var playWhenReady = false
    private var swapGeneration = 0

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }

    override suspend fun awaitReady() = ready

    override fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int) {
        calls += "setMediaQueue(${items.size}, $startIndex)"
        held = items
        swaps += items.map { it.metadata.title }
        failure.value = null
        index.value = startIndex
        position.value = 0
        duration.value = 0
        playing.value = false
        val mine = ++swapGeneration
        scope.launch {
            delay(readyAfterMs)
            if (mine == swapGeneration) {
                duration.value = itemDurationMs
                playing.value = playWhenReady
            }
        }
    }

    override fun play() {
        calls += "play"
        playWhenReady = true
        playing.value = duration.value > 0
    }

    override fun pause() {
        calls += "pause"
        playWhenReady = false
        playing.value = false
    }

    override fun seekTo(ms: Long) {
        calls += "seekTo($ms)"
        position.value = ms.coerceIn(0L, duration.value)
    }

    override fun skipBack() { calls += "skipBack" }
    override fun skipForward() { calls += "skipForward" }

    override fun skipToNext() {
        calls += "skipToNext"
        if (index.value + 1 < held.size) {
            index.value += 1
            position.value = 0
        }
    }

    override fun skipToPrevious() {
        calls += "skipToPrevious"
        if (index.value > 0) {
            index.value -= 1
            position.value = 0
        }
    }

    override fun release() { calls += "release" }

    /** The test lets the song play for [ms]. */
    fun playFor(ms: Long) {
        if (playing.value) position.value = (position.value + ms).coerceAtMost(duration.value)
    }

    fun fail(error: LightAudioError) {
        failure.value = error
        playing.value = false
    }

    fun clearError() {
        failure.value = null
    }
}

/** What a server would say for each song: a file straight away, or after a delay, or a stream, or an error. */
class FakeSources : SongSources {
    var startLatencyMs = 0L
    var queueLatencyMs = 0L
    var streamStart = false
    var failStart: Exception? = null
    var failQueue: Exception? = null
    val startCalls = CopyOnWriteArrayList<String>()
    val queueCalls = CopyOnWriteArrayList<List<String>>()

    private fun file(track: Track) = SongSource.OnPhone(File("/fake/${track.id}.mp3"))

    override suspend fun resolveStart(track: Track): SongSource {
        startCalls += track.id
        delay(startLatencyMs)
        failStart?.let { throw it }
        return if (streamStart) SongSource.Streamed("http://fake/${track.id}", transcoded = true) else file(track)
    }

    override suspend fun resolveQueue(tracks: List<Track>, current: Int, stillWanted: () -> Boolean): List<SongSource>? {
        queueCalls += tracks.map { it.id }
        delay(queueLatencyMs)
        if (!stillWanted()) return null
        failQueue?.let { throw it }
        return tracks.map(::file)
    }
}
