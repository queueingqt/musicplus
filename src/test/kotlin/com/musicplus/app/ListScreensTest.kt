package com.musicplus.app

import com.musicplus.app.data.AppAvailability
import com.musicplus.app.data.AvailableSplit
import com.musicplus.app.data.ListAvailability
import com.musicplus.app.data.OnPhoneIndex
import com.musicplus.app.data.ServerProfile
import com.musicplus.app.data.ServersNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What every list screen shares: the filter's semantics and the one line a list says when it is empty. */
class ListScreensTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @AfterTest
    fun stop() = scope.cancel()

    private data class Row(val name: String, val by: String?)

    private val rows = listOf(Row("Loading Screens", "The Fixtures"), Row("Sine Sessions", null), Row("Square Dance", "The Fixtures"))
    private val matches = { row: Row, query: String -> row.name.containsIgnoringCase(query) || row.by.containsIgnoringCase(query) }

    private suspend fun ListFilter.names(list: kotlinx.coroutines.flow.StateFlow<List<Row>>, count: Int) =
        withTimeout(5_000) { list.first { it.size == count } }.map { it.name }

    @Test
    fun aBlankQueryShowsEverything() = runBlocking<Unit> {
        val filter = ListFilter(scope)
        assertEquals(rows.map { it.name }, filter.names(filter.narrow(flowOf(rows), matches), 3))
    }

    @Test
    fun aQueryNarrowsToTheRowsThatMatchInAnyFieldIgnoringCase() = runBlocking<Unit> {
        val filter = ListFilter(scope)
        val narrowed = filter.narrow(flowOf(rows), matches)
        filter.set("sine")
        assertEquals(listOf("Sine Sessions"), filter.names(narrowed, 1))
        filter.set("FIXTURES")
        assertEquals(listOf("Loading Screens", "Square Dance"), filter.names(narrowed, 2))
        filter.set("zzz")
        assertEquals(emptyList(), filter.names(narrowed, 0))
        filter.set("")
        assertEquals(3, filter.names(narrowed, 3).size)
    }

    @Test
    fun oneQueryNarrowsEveryListItIsAppliedTo() = runBlocking<Unit> {
        val filter = ListFilter(scope)
        val albums = filter.narrow(flowOf(rows), matches)
        val others = filter.narrow(flowOf(listOf(Row("Sine wave", null), Row("Polka", null))), matches)
        filter.set("sine")
        assertEquals(1, filter.names(albums, 1).size)
        assertEquals(listOf("Sine wave"), filter.names(others, 1))
        assertEquals("sine", filter.query.value)
    }

    @Test
    fun aListThatChangesUnderTheFilterIsNarrowedAgain() = runBlocking<Unit> {
        val filter = ListFilter(scope)
        val source = MutableStateFlow(rows)
        val narrowed = filter.narrow(source, matches)
        filter.set("dance")
        assertEquals(listOf("Square Dance"), filter.names(narrowed, 1))
        source.value = rows + Row("Waltz Dance", null)
        assertEquals(listOf("Square Dance", "Waltz Dance"), filter.names(narrowed, 2))
    }

    @Test
    fun aMissingFieldNeverMatches() {
        assertFalse((null as String?).containsIgnoringCase("x"))
        assertTrue("Alpha".containsIgnoringCase("LPH"))
    }

    private val servers = listOf(ServerProfile("a", "Home", "http://a", "u", "p"), ServerProfile("b", "Work", "http://b", "u", "p"))

    private fun text(
        filter: String = "",
        configured: Boolean = true,
        enabled: Set<String> = setOf("a", "b"),
        synced: Map<String, Long> = mapOf("a" to 1L, "b" to 1L),
    ) = emptyListText("songs", filter, configured, enabled, servers, synced)

    @Test
    fun aFilterThatMatchedNothingWinsOverEveryOtherReason() {
        assertEquals("No matches", text(filter = "x", configured = false))
        assertEquals("No matches", text(filter = "x", enabled = emptySet()))
    }

    @Test
    fun noServerSavedThenNoneOnThenOneStillLoading() {
        assertTrue(text(configured = false).startsWith("No server yet"))
        assertTrue(text(enabled = emptySet()).startsWith("No server is on"))
        assertEquals("Loading Work…", text(synced = mapOf("a" to 1L)))
        assertEquals("Loading Home, Work…", text(synced = emptyMap()))
    }

    @Test
    fun aServerThatIsOffIsNotWaitedFor() {
        assertEquals("No songs yet", text(enabled = setOf("a"), synced = mapOf("a" to 1L)))
    }

    @Test
    fun everythingLoadedAndStillNothingSaysSo() {
        assertEquals("No songs yet", text())
    }

    @Test
    fun downloadedOnlyWithNothingDownloadedSaysSoButAFilterOrNoServerStillWins() {
        assertEquals("No downloaded songs", emptyListText("songs", "", true, setOf("a"), servers, mapOf("a" to 1L), downloadedOnly = true))
        assertEquals("No downloaded songs", emptyListText("songs", "", true, setOf("a"), servers, emptyMap(), downloadedOnly = true), "not 'Loading': what is on the phone needs no sync")
        assertEquals("No matches", emptyListText("songs", "x", true, setOf("a"), servers, emptyMap(), downloadedOnly = true))
        assertTrue(emptyListText("songs", "", true, emptySet(), servers, emptyMap(), downloadedOnly = true).startsWith("No server is on"))
    }

    private fun availability(down: Set<String>) =
        ListAvailability(OnPhoneIndex.EMPTY, ServersNow(anyKnown = true, enabled = setOf("up", "down"), unreachable = down), downloadedOnly = false)

    private fun track(id: String) = Track(id, id, null, null, null, null, null, 100, null, false, null, null)

    @Test
    fun aFilteredListIsSplitByWhatCanBePlayedAndFollowsTheServersComingAndGoing() = runBlocking<Unit> {
        val songs = listOf(track("up:1"), track("down:1"), track("up:2"), track("down:2"))
        AppAvailability.now.set(availability(down = setOf("down")))
        val filter = ListFilter(scope)
        val split = filter.narrowAndSplit(flowOf(songs), { t, q -> t.title.containsIgnoringCase(q) }, ListAvailability::songs)

        suspend fun next(ok: (AvailableSplit<Track>) -> Boolean) = withTimeout(5_000) { split.first(ok) }
        val down = next { it.unavailable.size == 2 }
        assertEquals(listOf("up:1", "up:2"), down.playable.map { it.id })
        assertEquals(listOf("down:1", "down:2"), down.unavailable.map { it.id })

        filter.set("2")
        val narrowed = next { it.playable.size + it.unavailable.size == 2 }
        assertEquals(listOf("up:2"), narrowed.playable.map { it.id })
        assertEquals(listOf("down:2"), narrowed.unavailable.map { it.id })

        AppAvailability.now.set(availability(down = emptySet()))
        val back = next { it.unavailable.isEmpty() }
        assertEquals(listOf("up:2", "down:2"), back.playable.map { it.id })
    }
}

