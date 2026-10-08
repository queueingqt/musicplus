package com.musicplus.app

import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The menu's state driven without a screen: rows replaced by key, one action at a time, one close, and an action that throws. */
class MenuStateTest {
    private fun row(key: String, label: String = key, select: ActionMenuSelection = ActionMenuSelection.Perform { null }, live: MutableSharedFlow<ActionMenuItem>? = null) =
        ActionMenuItem(icon = LightIcons.STAR, label = label, onSelect = select, liveUpdates = live, key = key)

    private suspend fun until(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(2) }

    @Test
    fun aTapReplacesJustItsRowWithWhatTheActionReturns() = runBlocking<Unit> {
        val flipped = row("favorite", "Remove from favorites")
        val favorite = row("favorite", "Add to favorites", ActionMenuSelection.Perform { flipped })
        val queue = row("queue")
        val state = MenuState(listOf(favorite, queue))
        state.perform("favorite", favorite.onSelect as ActionMenuSelection.Perform)
        assertEquals(listOf("Remove from favorites", "queue"), state.rows.value.map { it.label })
    }

    @Test
    fun anActionThatReturnsNothingDropsItsRow() = runBlocking<Unit> {
        val gone = row("remove", select = ActionMenuSelection.Perform { null })
        val state = MenuState(listOf(gone, row("other")))
        state.perform("remove", gone.onSelect as ActionMenuSelection.Perform)
        assertEquals(listOf("other"), state.rows.value.map { it.key })
    }

    @Test
    fun anActionThatThrowsDoesNotLeaveTheMenuDead() = runBlocking<Unit> {
        var calls = 0
        val flaky = row("flaky", select = ActionMenuSelection.Perform { calls++; if (calls == 1) error("server hiccup") else row("flaky", "worked") })
        val state = MenuState(listOf(flaky))
        state.perform("flaky", flaky.onSelect as ActionMenuSelection.Perform)
        assertEquals(listOf("flaky"), state.rows.value.map { it.label }, "the row is unchanged")
        state.perform("flaky", flaky.onSelect as ActionMenuSelection.Perform)
        assertEquals(listOf("worked"), state.rows.value.map { it.label }, "and a second tap still works: busy was cleared")
    }

    @Test
    fun aSecondTapWhileOneIsRunningIsIgnored() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var ran = 0
        val slow = row("slow", select = ActionMenuSelection.Perform { ran++; release.await(); null })
        val state = MenuState(listOf(slow, row("other")))
        val first = async(Dispatchers.Default) { state.perform("slow", slow.onSelect as ActionMenuSelection.Perform) }
        until { ran == 1 }
        state.perform("slow", slow.onSelect as ActionMenuSelection.Perform)
        assertEquals(1, ran, "ignored while the first runs")
        release.complete(Unit)
        first.await()
    }

    @Test
    fun aLiveUpdateThatArrivesWhileAnActionRunsDoesNotMakeTheActionsResultVanish() = runBlocking<Unit> {
        val live = MutableSharedFlow<ActionMenuItem>(extraBufferCapacity = 4)
        val release = CompletableDeferred<Unit>()
        val download = row("download", "Download", select = ActionMenuSelection.Perform { release.await(); row("download", "Downloaded") }, live = live)
        val state = MenuState(listOf(download))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            scope.launch { state.followLiveUpdates() }
            val running = async(Dispatchers.Default) { state.perform("download", download.onSelect as ActionMenuSelection.Perform) }
            delay(50)
            live.emit(row("download", "Downloading"))
            until { state.rows.value.single().label == "Downloading" }
            release.complete(Unit)
            running.await()
            assertEquals("Downloaded", state.rows.value.single().label, "matched by key, so the result lands on the row the live update put there")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLiveUpdateReplacesItsRowByKeyEvenAfterItsLabelChanged() = runBlocking<Unit> {
        val live = MutableSharedFlow<ActionMenuItem>(extraBufferCapacity = 4)
        val state = MenuState(listOf(row("download", "Download", live = live), row("other")))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            scope.launch { state.followLiveUpdates() }
            delay(50)
            live.emit(row("download", "Queued"))
            until { state.rows.value.first().label == "Queued" }
            live.emit(row("download", "Downloaded"))
            until { state.rows.value.first().label == "Downloaded" }
            assertEquals(listOf("Downloaded", "other"), state.rows.value.map { it.label })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backAndNavigationShareOneCloseLatch() {
        val state = MenuState(listOf(row("a")))
        var opened = 0
        var wentBack = 0
        val navigate = ActionMenuSelection.Navigate { opened++ }
        state.navigate(navigate) { wentBack++ }
        state.navigate(navigate) { wentBack++ }
        assertEquals(1, wentBack, "a second tap must not pop the caller's screen too")
        assertEquals(1, opened)
        assertFalse(state.beginClose(), "back after a navigation is also ignored")
    }

    @Test
    fun backIsOnlyAllowedOnce() {
        val state = MenuState(listOf(row("a")))
        assertTrue(state.beginClose())
        assertFalse(state.beginClose())
    }

    @Test
    fun navigationWaitsOutARunningAction() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var started = false
        val slow = row("slow", select = ActionMenuSelection.Perform { started = true; release.await(); null })
        val state = MenuState(listOf(slow))
        val running = async(Dispatchers.Default) { state.perform("slow", slow.onSelect as ActionMenuSelection.Perform) }
        until { started }
        var opened = 0
        state.navigate(ActionMenuSelection.Navigate { opened++ }) {}
        assertEquals(0, opened, "ignored while an action runs")
        release.complete(Unit)
        running.await()
    }
}
