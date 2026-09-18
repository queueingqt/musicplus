package com.musicplus.app

import com.musicplus.app.data.DownloadStatus
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons

/**
 * Builds a self-updating "Add ... to queue" row — [label] is e.g. "Add to
 * queue" for a single track or "Add album to queue" for a whole album; [add]
 * does the actual enqueueing. A [ActionMenuSelection.Perform] row that has no
 * state of its own to flip still has to return *something* to replace itself
 * with — here, itself unchanged — which means the row's own reference has to
 * exist before its own `onSelect` closure can capture it. Every "add to
 * queue" row in the app (album list, artist detail, both of album detail's
 * album- and track-level rows, songs list, favorites) used to declare its own
 * `lateinit var` to do this; this is that self-reference idiom owned once
 * instead of hand-copied at every call site.
 */
fun addToQueueActionItem(label: String, add: suspend () -> Unit): ActionMenuItem {
    lateinit var item: ActionMenuItem
    item = ActionMenuItem(
        icon = LightIcons.ADD,
        label = label,
        onSelect = ActionMenuSelection.Perform {
            add()
            item
        },
    )
    return item
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
