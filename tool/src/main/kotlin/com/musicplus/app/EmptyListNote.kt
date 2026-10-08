package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.musicplus.app.data.AppDisplayPrefs
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.ServerProfile
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

/**
 * The one plain line a list shows when it has nothing to show, saying why: no server saved, none switched on, a
 * server that has not loaded yet, or a filter that matched nothing. [what] names the list ("songs"). No banner: a
 * list that is empty for a reason should say so where the rows would be.
 *
 * While the list is not [loaded] yet it is not empty, only unanswered, so it says nothing, and "Loading…" only if that takes long enough
 * to look like a blank screen (a cold start's Songs takes seconds). Saying "No songs yet" then, as it used to, was a lie.
 */
@Composable
fun EmptyListNote(what: String, filter: String = "", loaded: Boolean = true) {
    if (!loaded) {
        LoadingNote()
        return
    }
    val configured by AppServerPrefs.isConfigured.value.collectAsState()
    val enabled by AppServerPrefs.enabledServerIds.value.collectAsState()
    val servers by AppServerPrefs.servers.value.collectAsState()
    val synced by AppServerPrefs.lastSyncedAt.value.collectAsState()
    val downloadedOnly by AppDisplayPrefs.downloadedOnly.value.collectAsState()
    LightText(
        text = emptyListText(what, filter, configured, enabled, servers, synced, downloadedOnly),
        variant = LightTextVariant.Fine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** How long a list may take to load before it says so: shorter than that, a "Loading…" line is only another flash. */
private const val LOADING_NOTE_DELAY_MS = 500L

@Composable
private fun LoadingNote() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_NOTE_DELAY_MS)
        visible = true
    }
    if (visible) {
        LightText(
            text = "Loading…",
            variant = LightTextVariant.Fine,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        )
    }
}

/**
 * Why a list is empty, said plainly: a filter that matched nothing, no server saved, none switched on, "Downloaded only" with nothing
 * downloaded, a server whose lists have not loaded yet (by name), or just that there is nothing yet. In that order, so the most specific
 * reason wins.
 */
fun emptyListText(
    what: String,
    filter: String,
    configured: Boolean,
    enabled: Set<String>,
    servers: List<ServerProfile>,
    lastSyncedAt: Map<String, Long>,
    downloadedOnly: Boolean = false,
): String = when {
    filter.isNotBlank() -> "No matches"
    !configured -> "No server yet. Add one in Settings → Server."
    enabled.isEmpty() -> "No server is on. Turn one on in Settings → Server."
    downloadedOnly -> "No downloaded $what"
    else -> {
        val loading = servers.filter { it.id in enabled && lastSyncedAt[it.id] == null }.map { it.name }
        if (loading.isNotEmpty()) "Loading ${loading.joinToString(", ")}…" else "No $what yet"
    }
}
