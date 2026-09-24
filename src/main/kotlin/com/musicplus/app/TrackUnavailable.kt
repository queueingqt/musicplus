package com.musicplus.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.ServerScope
import com.musicplus.app.data.TrackAvailability

/** What a tap on an unavailable song says. */
const val SERVER_NOT_REACHABLE_NOTE = "Server not reachable"

/**
 * True when [track] cannot be played right now: its server is off or cannot be reached, and the phone has neither a
 * finished download nor a copy kept from streaming it. Rows grey such a song out. The file lookup only happens for a song whose
 * server is out, so nothing extra is read while every server is fine.
 */
@Composable
fun rememberTrackUnavailable(track: Track): Boolean {
    val unreachable by AppServerPrefs.unreachableServerIds.value.collectAsState()
    val on by AppServerPrefs.enabledServerIds.value.collectAsState()
    val servers by AppServerPrefs.servers.value.collectAsState()
    val serverOut = !TrackAvailability.serverUsable(ServerScope.serverOf(track.id), servers.isNotEmpty(), on, unreachable)
    return remember(track.id, track.downloadStatus, track.localFilePath, serverOut) {
        serverOut && !TrackAvailability.hasAudioOnPhone(track)
    }
}
