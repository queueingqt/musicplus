package com.musicplus.app.data

import com.thelightphone.sdk.LightConnectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides which whole-song fetches run, in what order, and how many at once —
 * for both fetches that playback needs and downloads the person asked for.
 *
 * Before this, every download was its own background job with no ordering and no
 * limit (63 queued together ran up to 47 at once), and a playback fetch had to
 * share the connection with all of them. Now:
 *
 * **Order.** The song that is playing comes first, then the next few in the queue
 * ([Priority.UP_NEXT]), then the rest of the queue, then songs the person asked
 * to download, then retries. A download that turns out to be the playing or an
 * up-next song is promoted (the rank is re-read while it waits and while it
 * runs).
 *
 * **Two lanes.** Playback fetches ([Lane.PLAYBACK]) and downloads ([Lane.BULK]) are
 * counted separately, so a backlog of downloads can never keep the next song from
 * starting. The playing song is never made to wait for a slot at all.
 *
 * **Bandwidth.** A transfer that is outranked by a running one pauses reading
 * between chunks (the sender backs off on its own) instead of fighting it for the
 * connection; it gives up pausing after [MAX_PAUSE_MS] so its own idle timeout
 * never fires.
 *
 * **How many at once** adapts to the connection. It starts at [WIFI_START] on
 * Wi-Fi and [OTHER_START] otherwise, and while the slots are full it probes: add a
 * few, watch the smoothed total throughput for [PROBE_WINDOWS] windows, keep them
 * only if that bought at least [GAIN] more, otherwise take them back and hold for
 * [HOLD_WINDOWS] windows before trying again. So a slow cellular link settles at
 * a couple of transfers and Wi-Fi climbs toward [WIFI_MAX] while it keeps paying
 * off. A timeout or dropped connection halves the limit. Windows in which a
 * playback fetch was running are not measured (the pausing distorts them), and no
 * new download is started while one is. Fetches that make the server transcode
 * ([Lease.transcoding]) are also capped at [TRANSCODE_CAP] so a NAS isn't asked to
 * run dozens of encoders.
 */
object FetchGate {
    /** Lower ordinal = more important. Compared by ordinal. */
    enum class Priority { NOW_PLAYING, UP_NEXT, QUEUE, DOWNLOAD, RETRY }

    enum class Lane { PLAYBACK, BULK }

    class Lease internal constructor(
        internal val lane: Lane,
        internal val rank: () -> Priority,
        internal val transcoding: Boolean,
        internal val key: String,
    ) {
        internal val bytes = AtomicLong(0)
        internal var startedAtMs = 0L
        private var lastCheckMs = 0L

        /** Call between chunks: pauses while a more important transfer is running. */
        suspend fun checkpoint() {
            val now = System.currentTimeMillis()
            if (now - lastCheckMs < CHECK_EVERY_MS) return
            lastCheckMs = now
            pauseIfOutranked(this)
            lastCheckMs = System.currentTimeMillis()
        }

        /** Call with the size of each chunk received — feeds the throughput measurement. */
        fun bytes(n: Int) {
            bytes.addAndGet(n.toLong())
            windowBytes.addAndGet(n.toLong())
        }
    }

    private class Waiter(
        val lane: Lane,
        val rank: () -> Priority,
        val transcoding: Boolean,
        val key: String,
        val seq: Long,
    ) {
        val ready = CompletableDeferred<Lease>()
    }

    private const val TAG = "FetchGate"
    private const val WIFI_START = 6
    private const val WIFI_MAX = 40
    private const val WIFI_STEP = 4
    private const val OTHER_START = 2
    private const val OTHER_MAX = 6
    private const val OTHER_STEP = 1
    private const val TRANSCODE_CAP = 4
    private const val WINDOW_MS = 3_000L
    private const val GAIN = 1.10
    private const val PROBE_WINDOWS = 2
    private const val HOLD_WINDOWS = 5
    private const val MAX_PAUSE_MS = 40_000L
    private const val PAUSE_POLL_MS = 200L
    private const val CHECK_EVERY_MS = 250L

    private val lock = Any()
    private val waiting = ArrayList<Waiter>()
    private val running = ArrayList<Lease>()
    private var nextSeq = 0L

    /** Slots for the [Lane.BULK] lane; the playback lane uses at most [TRANSCODE_CAP] of them. */
    private var limit = OTHER_START
    private var onWifi = false
    private val windowBytes = AtomicLong(0)

    // Adaptation state (all under [lock]).
    private var windowIndex = 0
    private var smoothedBytesPerSec = 0.0
    private var probeBaseline = 0.0
    private var probePending = false
    private var probeStartedAtWindow = 0
    private var holdUntilWindow = 0

    @Volatile private var connectivity: LightConnectivity? = null
    private var controller: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Lets the gate see the connection type. Safe to call more than once. */
    fun attach(connectivity: LightConnectivity) {
        this.connectivity = connectivity
        synchronized(lock) { applyConnectionLocked(detectWifi()) }
    }

    /**
     * Waits for a slot, runs [block] holding it, and returns its result — or null if
     * no slot came free within [maxWaitMs] (the caller should try again later).
     * [rank] is read again whenever the gate decides, so a song that starts playing
     * while it waits is served first.
     */
    suspend fun <T> run(
        lane: Lane,
        key: String,
        rank: () -> Priority,
        transcoding: Boolean = false,
        maxWaitMs: Long = 8 * 60_000L,
        block: suspend (Lease) -> T,
    ): T? {
        val waiter = synchronized(lock) {
            val w = Waiter(lane, rank, transcoding, key, nextSeq++)
            waiting += w
            ensureControllerLocked()
            dispatchLocked()
            w
        }
        val lease = try {
            withTimeoutOrNull(maxWaitMs) { waiter.ready.await() }
        } catch (e: CancellationException) {
            synchronized(lock) {
                waiting.remove(waiter)
                // Cancelled after a slot was granted but before it was used: hand it back.
                if (waiter.ready.isCompleted && !waiter.ready.isCancelled) {
                    running.remove(waiter.ready.getCompleted())
                    dispatchLocked()
                }
            }
            throw e
        }
        if (lease == null) {
            // Granted in the instant the wait timed out: use it rather than leak the slot.
            val grantedJustNow = synchronized(lock) {
                waiting.remove(waiter)
                waiter.ready.isCompleted && !waiter.ready.isCancelled
            }
            if (grantedJustNow) return runWith(waiter.ready.getCompleted(), block)
            AppLogger.d(TAG, "no slot for ${waiter.key} within ${maxWaitMs / 1000} s")
            return null
        }
        return runWith(lease, block)
    }

    private suspend fun <T> runWith(lease: Lease, block: suspend (Lease) -> T): T {
        lease.startedAtMs = System.currentTimeMillis()
        AppLogger.d(TAG, "start ${lease.rank()} ${lease.key} (${snapshotText()})")
        try {
            return block(lease)
        } finally {
            val seconds = (System.currentTimeMillis() - lease.startedAtMs) / 1000.0
            synchronized(lock) {
                running.remove(lease)
                dispatchLocked()
            }
            AppLogger.d(TAG, "end ${lease.key} ${"%.1f".format(lease.bytes.get() / 1e6)} MB in ${"%.1f".format(seconds)} s")
        }
    }

    /** A transfer failed in a way that suggests the link is overloaded (timeout, dropped connection): back off. */
    fun onCongestion() {
        synchronized(lock) {
            val next = maxOf(minLimit(), limit / 2)
            if (next != limit) {
                AppLogger.d(TAG, "congestion: limit $limit -> $next")
                limit = next
            }
            probePending = false
            holdUntilWindow = windowIndex + 2 * HOLD_WINDOWS
        }
    }

    private fun minLimit() = if (onWifi) WIFI_START / 2 else OTHER_START

    // --- scheduling ---------------------------------------------------------

    private fun dispatchLocked() {
        if (waiting.isEmpty()) return
        val ordered = waiting.sortedWith(compareBy<Waiter>({ it.rank().ordinal }, { it.seq }))
        var playbackRunning = running.count { it.lane == Lane.PLAYBACK && it.rank() != Priority.NOW_PLAYING }
        var bulkRunning = running.count { it.lane == Lane.BULK }
        var transcodes = running.count { it.transcoding && it.rank() != Priority.NOW_PLAYING }
        val playbackCap = minOf(limit, TRANSCODE_CAP)
        for (w in ordered) {
            val isNow = w.rank() == Priority.NOW_PLAYING
            if (!isNow) {
                // A new download would only open a connection to be paused at once, and
                // off Wi-Fi the playing song's fetch should have the link to itself.
                if (w.lane == Lane.BULK && running.any { it.lane == Lane.PLAYBACK }) continue
                if (!onWifi && running.any { it.rank() == Priority.NOW_PLAYING }) continue
                if (w.transcoding && transcodes >= TRANSCODE_CAP) continue
                when (w.lane) {
                    Lane.PLAYBACK -> if (playbackRunning >= playbackCap) continue
                    Lane.BULK -> if (bulkRunning >= limit) continue
                }
            }
            waiting.remove(w)
            val lease = Lease(w.lane, w.rank, w.transcoding, w.key)
            running += lease
            if (!isNow) {
                if (w.lane == Lane.PLAYBACK) playbackRunning++ else bulkRunning++
                if (w.transcoding) transcodes++
            }
            w.ready.complete(lease)
        }
    }

    private suspend fun pauseIfOutranked(lease: Lease) {
        var waited = 0L
        while (waited < MAX_PAUSE_MS) {
            val mine = lease.rank()
            if (mine == Priority.NOW_PLAYING) return
            val outranked = synchronized(lock) { running.any { it !== lease && it.rank().ordinal < mine.ordinal } }
            if (!outranked) return
            delay(PAUSE_POLL_MS)
            waited += PAUSE_POLL_MS
        }
    }

    // --- adapting to the connection ---------------------------------------------

    private fun detectWifi(): Boolean =
        try {
            connectivity?.currentStatus?.isWifi == true
        } catch (e: Exception) {
            false
        }

    private fun applyConnectionLocked(wifi: Boolean) {
        onWifi = wifi
        limit = if (wifi) WIFI_START else OTHER_START
        smoothedBytesPerSec = 0.0
        probePending = false
        holdUntilWindow = windowIndex + 1
        AppLogger.d(TAG, "connection is ${if (wifi) "Wi-Fi" else "not Wi-Fi"}: starting at $limit at a time")
    }

    private fun ensureControllerLocked() {
        if (controller?.isActive == true) return
        controller = scope.launch {
            while (true) {
                delay(WINDOW_MS)
                val idle = synchronized(lock) {
                    if (running.isEmpty() && waiting.isEmpty()) {
                        controller = null
                        smoothedBytesPerSec = 0.0
                        probePending = false
                        true
                    } else {
                        false
                    }
                }
                if (idle) return@launch
                adaptOnce()
            }
        }
    }

    private fun adaptOnce() {
        val perSec = windowBytes.getAndSet(0) * 1000.0 / WINDOW_MS
        val wifiNow = detectWifi()
        synchronized(lock) {
            windowIndex++
            if (wifiNow != onWifi) {
                AppLogger.d(TAG, "connection changed to ${if (wifiNow) "Wi-Fi" else "not Wi-Fi"}")
                applyConnectionLocked(wifiNow)
                dispatchLocked()
                return
            }
            val playbackActive = running.any { it.lane == Lane.PLAYBACK }
            val bulkRunning = running.count { it.lane == Lane.BULK }
            val saturated = bulkRunning >= limit && waiting.any { it.lane == Lane.BULK }
            if (playbackActive || !saturated) {
                // Not a fair measurement of what the link can carry: start over.
                smoothedBytesPerSec = 0.0
                probePending = false
                dispatchLocked()
                return
            }
            smoothedBytesPerSec = if (smoothedBytesPerSec == 0.0) perSec else 0.5 * smoothedBytesPerSec + 0.5 * perSec
            val max = if (onWifi) WIFI_MAX else OTHER_MAX
            val step = if (onWifi) WIFI_STEP else OTHER_STEP
            if (probePending) {
                if (windowIndex - probeStartedAtWindow >= PROBE_WINDOWS) {
                    probePending = false
                    if (smoothedBytesPerSec < probeBaseline * GAIN) {
                        val next = maxOf(minLimit(), limit - step)
                        AppLogger.d(TAG, "limit $limit -> $next (${"%.1f".format(smoothedBytesPerSec / 1e6)} MB/s, no gain from the extra; holding)")
                        limit = next
                        holdUntilWindow = windowIndex + HOLD_WINDOWS
                    }
                }
            } else if (windowIndex >= holdUntilWindow && limit < max) {
                probeBaseline = smoothedBytesPerSec
                val next = minOf(max, limit + step)
                AppLogger.d(TAG, "limit $limit -> $next (probing; ${"%.1f".format(smoothedBytesPerSec / 1e6)} MB/s now, ${waiting.size} waiting)")
                limit = next
                probeStartedAtWindow = windowIndex
                probePending = true
            }
            // Ranks can change while a song waits (the queue moved on), and slots may have opened.
            dispatchLocked()
        }
    }

    private fun snapshotText(): String =
        synchronized(lock) {
            "running ${running.size} (${running.count { it.lane == Lane.PLAYBACK }} playback), waiting ${waiting.size}, limit $limit"
        }
}
