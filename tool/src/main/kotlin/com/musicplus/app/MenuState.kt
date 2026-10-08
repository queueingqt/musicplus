package com.musicplus.app

import com.musicplus.app.data.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The state of one open [ActionsMenuScreen]: its rows, the latch that lets a back or a [ActionMenuSelection.Navigate] act only once, and the
 * latch that lets one [ActionMenuSelection.Perform] run at a time. It lived inside the screen's `Content()`, where none of it could be driven
 * without a device, and where a `Perform` that threw left the busy latch set so every row was dead until the person went back.
 *
 * A row is found by its [ActionMenuItem.key], never by identity or label: a tap or a live update is exactly what changes a row, and an
 * identity match silently dropped a tap's result when a live update replaced the row while the action was still running.
 */
class MenuState(private val original: List<ActionMenuItem>) {
    private val _rows = MutableStateFlow(original)
    val rows: StateFlow<List<ActionMenuItem>> = _rows

    private var closing = false
    private var busy = false

    /**
     * Keeps rows with a live source up to date for as long as the caller stays in this call. Subscribed off the *original* list, not the
     * current rows, so it survives any number of replacements of the row it is watching.
     */
    suspend fun followLiveUpdates() = coroutineScope {
        original.forEach { item ->
            val live = item.liveUpdates ?: return@forEach
            launch { live.collect { updated -> replace(item.key, updated) } }
        }
    }

    /** True the first time, false once the screen is already being closed: the back button and every [ActionMenuSelection.Navigate] row share one latch. */
    fun beginClose(): Boolean {
        if (closing) return false
        closing = true
        return true
    }

    /**
     * Runs [selection] for the row [key]: waits for nothing else (a second tap while one is running is ignored), replaces the row with
     * what the action returns (or drops it when it returns null), and always clears the latch, whatever the action did. An action that
     * throws leaves the rows as they were and is logged, not allowed to take the app down with it.
     */
    suspend fun perform(key: String, selection: ActionMenuSelection.Perform) {
        if (closing || busy) return
        busy = true
        try {
            val updated = try {
                selection.action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ActionsMenu", "action for \"$key\" failed", e)
                return
            }
            if (updated != null) replace(key, updated) else _rows.value = _rows.value.filter { it.key != key }
        } finally {
            busy = false
        }
    }

    /**
     * Leaves this menu for another screen: [goBack] first, so the next screen lands directly on top of the one this menu was opened from
     * and not on top of this menu (its own back would then only return here). Ignored while an action runs or once closing.
     */
    fun navigate(selection: ActionMenuSelection.Navigate, goBack: () -> Unit) {
        if (busy || !beginClose()) return
        goBack()
        selection.open()
    }

    private fun replace(key: String, updated: ActionMenuItem) {
        _rows.value = _rows.value.map { if (it.key == key) updated else it }
    }
}
