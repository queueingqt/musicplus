package com.musicplus.app.data

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one refresh runner, with fake servers: who it asks, that one server's failure never touches another, and that it says which refreshes worked. */
class ServerRefreshTest {
    private val one = StubMusicApi("one")
    private val two = StubMusicApi("two")
    private val ran = CopyOnWriteArrayList<String>()
    private val refreshed = CopyOnWriteArrayList<String>()
    private var connected = true
    private var saved = setOf("one", "two")

    private fun refresh(shown: List<String> = listOf("one", "two"), apis: Map<String, MusicApi> = mapOf("one" to one, "two" to two)) =
        ServerRefresh(
            isConnected = { connected },
            apis = FakeApiLookup(apis),
            shownServerIds = flowOf(shown),
            onRefreshed = { refreshed += it },
            savedServerIds = { saved },
        )

    @Test
    fun offlineNothingRuns() = runBlocking<Unit> {
        connected = false
        refresh().run("lists") { ran += it.serverId }
        assertTrue(ran.isEmpty() && refreshed.isEmpty())
    }

    @Test
    fun aListRefreshCoversEveryServerThatIsOn() = runBlocking<Unit> {
        refresh().run("lists") { ran += it.serverId }
        assertEquals(setOf("one", "two"), ran.toSet())
        assertEquals(setOf("one", "two"), refreshed.toSet())
    }

    @Test
    fun aRefreshOfOneItemGoesToTheServerThatOwnsIt() = runBlocking<Unit> {
        refresh().run("album", ownerId = "two:al-1") { ran += it.serverId }
        assertEquals(listOf("two"), ran.toList())
        assertEquals(listOf("two"), refreshed.toList())
    }

    @Test
    fun aServerThatFailsIsLoggedNotPropagatedAndNotMarkedRefreshedWhileTheOtherStillRuns() = runBlocking<Unit> {
        refresh().run("lists") { api ->
            if (api.serverId == "one") throw IOException("down")
            ran += api.serverId
        }
        assertEquals(listOf("two"), ran.toList())
        assertEquals(listOf("two"), refreshed.toList())
    }

    @Test
    fun aServerWithNoApiIsSkipped() = runBlocking<Unit> {
        refresh(shown = listOf("one", "gone")).run("lists") { ran += it.serverId }
        assertEquals(listOf("one"), ran.toList())
    }

    @Test
    fun aRefreshMustNotWriteBackForAServerThatWasRemovedWhileItRan() {
        val runner = refresh()
        assertTrue(runner.live(one))
        saved = setOf("two")
        assertFalse(runner.live(one))
        assertTrue(runner.live(two))
    }
}
