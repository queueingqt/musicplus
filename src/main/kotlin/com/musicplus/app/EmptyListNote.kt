package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.musicplus.app.data.AppServerPrefs
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The one plain line a list shows when it has nothing to show, saying why: no server saved, none switched on, a
 * server that has not loaded yet, or a filter that matched nothing. [what] names the list ("songs"). No banner: a
 * list that is empty for a reason should say so where the rows would be.
 */
@Composable
fun EmptyListNote(what: String, filter: String = "") {
    val configured by AppServerPrefs.isConfigured.value.collectAsState()
    val enabled by AppServerPrefs.enabledServerIds.value.collectAsState()
    val servers by AppServerPrefs.servers.value.collectAsState()
    val synced by AppServerPrefs.lastSyncedAt.value.collectAsState()
    val text = when {
        filter.isNotBlank() -> "No matches"
        !configured -> "No server yet. Add one in Settings → Server."
        enabled.isEmpty() -> "No server is on. Turn one on in Settings → Server."
        else -> {
            val loading = servers.filter { it.id in enabled && synced[it.id] == null }.map { it.name }
            if (loading.isNotEmpty()) "Loading ${loading.joinToString(", ")}…" else "No $what yet"
        }
    }
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}
