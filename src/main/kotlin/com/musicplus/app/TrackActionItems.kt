package com.musicplus.app

import com.musicplus.app.data.DownloadStatus
import com.musicplus.app.data.PlaybackRepository
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModalManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Duration.Companion.seconds

/**
 * Builds an "Add ... to queue" row — [label] is e.g. "Add to queue" for a
 * single track or "Add album to queue" for a whole album; [tracks] supplies
 * what to add. Every "add to queue" row in the app (album list, artist
 * detail, both of album detail's album- and track-level rows, songs list,
 * favorites, search, playlists) goes through this one builder.
 *
 * If any of those tracks are already in the queue, the row asks first with the
 * app's standard confirmation — "Add anyway?" — confirm to add them again,
 * cancel (or let it time out) to leave the queue as it is.
 *
 * Once the tracks are added, the row relabels itself "Added ... to queue" for
 * as long as that menu stays open (issue #49 — the row used to come back
 * exactly as it was, so nothing showed that anything had happened); the icon
 * does not change. Nothing is remembered beyond that: each time a menu opens
 * its rows are built fresh, so re-opening the menu shows "Add to queue" again.
 * Both states share [label] as their [ActionMenuItem.key].
 */
fun addToQueueActionItem(
    label: String,
    playback: PlaybackRepository,
    tracks: suspend () -> List<Track>,
): ActionMenuItem {
    // A confirmed add happens later, from the dialog's callback, so it can't be
    // this row's own Perform result — liveUpdates is how the menu takes the
    // "added" row in that case.
    val confirmedAdd = MutableSharedFlow<ActionMenuItem>(extraBufferCapacity = 1)
    lateinit var added: ActionMenuItem
    lateinit var ready: ActionMenuItem
    added = ActionMenuItem(
        key = label,
        icon = LightIcons.ADD,
        label = label.replaceFirst("Add", "Added"),
        onSelect = ActionMenuSelection.Perform { added },
    )
    ready = ActionMenuItem(
        key = label,
        icon = LightIcons.ADD,
        label = label,
        liveUpdates = confirmedAdd,
        onSelect = ActionMenuSelection.Perform {
            val toAdd = tracks()
            if (toAdd.isEmpty()) return@Perform ready
            // A queue still being restored from disk looks empty — see awaitQueueRestored.
            playback.awaitQueueRestored()
            val queuedIds = playback.currentSnapshot().queue.mapTo(HashSet()) { it.id }
            val alreadyQueued = toAdd.count { it.id in queuedIds }
            if (alreadyQueued == 0) {
                playback.addToQueue(toAdd)
                added
            } else {
                LightModalManager.show(
                    ConfirmModal(
                        title = "Already in queue",
                        message = alreadyInQueueMessage(toAdd, alreadyQueued),
                        confirmContentDescription = "Add anyway",
                        onConfirm = { playback.addToQueueAsync(toAdd) { confirmedAdd.tryEmit(added) } },
                    ),
                    duration = 30.seconds,
                )
                ready
            }
        },
    )
    return ready
}

private fun alreadyInQueueMessage(tracks: List<Track>, alreadyQueued: Int): String = when {
    tracks.size == 1 -> "\"${tracks[0].title}\" is already in the queue. Add it anyway?"
    alreadyQueued == tracks.size -> "All ${tracks.size} songs are already in the queue. Add them anyway?"
    else -> "$alreadyQueued of ${tracks.size} songs are already in the queue. Add them all anyway?"
}

/**
 * Three real visual states, not two: QUEUED/DOWNLOADING now render distinctly
 * from both "not downloaded" and "downloaded" instead of only toggling
 * between DOWNLOAD_ARROW/DOWNLOADED_ARROW. Uses REFRESH for "in progress" —
 * LOOP was tried first but is the exact same icon the Now Playing screen uses
 * for Repeat, which on-device looked like a stray repeat toggle appearing on
 * tracks whenever an album download was running. There's no dedicated
 * spinner/progress icon in LightIcons; REFRESH isn't used anywhere else in
 * this app, so it doesn't collide.
 *
 * PlaylistDetailScreen's own copy of this had drifted down to only two states
 * (COMPLETE vs. everything else, both for the action-menu row and the
 * always-visible row glyph) — a track actively downloading inside a playlist
 * showed the exact same icon as one not yet started at all, while the
 * identical screen for albums/songs already showed a distinct in-progress
 * icon. Consolidating every call site onto this version fixes that.
 */
fun downloadStatusIcon(status: DownloadStatus?): LightIconConfiguration = when (status) {
    DownloadStatus.COMPLETE -> LightIcons.DOWNLOADED_ARROW
    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> LightIcons.REFRESH
    DownloadStatus.FAILED, null -> LightIcons.DOWNLOAD_ARROW
}

/** Tap semantics: QUEUED/DOWNLOADING/COMPLETE -> stop or remove; FAILED/null -> start. See each screen's own `toggleDownload`. */
fun downloadStatusLabel(status: DownloadStatus?): String = when (status) {
    null -> "Download"
    DownloadStatus.QUEUED -> "Queued — tap to cancel"
    DownloadStatus.DOWNLOADING -> "Downloading — tap to cancel"
    DownloadStatus.COMPLETE -> "Downloaded — tap to remove"
    DownloadStatus.FAILED -> "Failed — tap to retry"
}

/**
 * Per-track counterpart to [trackListDownloadActionItem] (TrackListDownload.kt)
 * — same self-updating shape (the row rebuilds itself from whatever [toggle]
 * returns), but for one track's own [DownloadStatus] rather than a whole
 * album/playlist's aggregate state. [toggle] receives the status this row was
 * just showing and returns the new one — the same contract as each screen's
 * own `toggleDownload(lightContext, track, currentStatus)`, which stays put
 * on each ViewModel rather than moving here, since it's the one part of this
 * that actually needs repository/lightContext access. Callers wanting this
 * row to also track a live status (the same `liveUpdates` pattern
 * [trackListDownloadActionItem] uses) attach it themselves, e.g.
 * `trackDownloadActionItem(status, toggle).copy(liveUpdates = statusFlow.map
 * { trackDownloadActionItem(it, toggle) })`.
 */
fun trackDownloadActionItem(
    status: DownloadStatus?,
    toggle: suspend (DownloadStatus?) -> DownloadStatus?,
): ActionMenuItem = ActionMenuItem(
    key = "download",
    icon = downloadStatusIcon(status),
    label = downloadStatusLabel(status),
    onSelect = ActionMenuSelection.Perform {
        trackDownloadActionItem(toggle(status), toggle)
    },
)
