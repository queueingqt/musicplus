package com.musicplus.app

import com.musicplus.app.data.PlaybackRepository
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.flow.MutableSharedFlow

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
                confirm(
                    title = "Already in queue",
                    message = alreadyInQueueMessage(toAdd, alreadyQueued),
                    confirmContentDescription = "Add anyway",
                    onConfirm = { playback.addToQueueAsync(toAdd) { confirmedAdd.tryEmit(added) } },
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
