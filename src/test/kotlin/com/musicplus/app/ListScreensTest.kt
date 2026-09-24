package com.musicplus.app

import com.musicplus.app.data.AppAvailability
import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.WarmedFlow
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
    fun theWarmedSplitFollowsTheServersComingAndGoing() = runBlocking<Unit> {
        val songs = listOf(track("up:1"), track("down:1"), track("up:2"), track("down:2"))
        AppAvailability.now.set(availability(down = setOf("down")))
        val split = AppAvailability.split(MutableStateFlow(songs), flowOf(true), ListAvailability::songs)
        val down = withTimeout(5_000) { split.first { it.unavailable.size == 2 } }
        assertEquals(listOf("up:1", "up:2"), down.playable.map { it.id })
        assertEquals(listOf("down:1", "down:2"), down.unavailable.map { it.id })

        AppAvailability.now.set(availability(down = emptySet()))
        val back = withTimeout(5_000) { split.first { it.unavailable.isEmpty() } }
        assertEquals(songs.map { it.id }, back.playable.map { it.id })
    }

    @Test
    fun aSplitListStartsFromItsRowsNotFromNothing() {
        // An empty first value is drawn as "No songs yet" until the real rows arrive a few frames later (100-280 ms on the phone).
        val source = MutableStateFlow(AvailableSplit(listOf(track("up:1")), listOf(track("down:1"))))
        val narrowed = ListFilter(scope).narrowSplit(source) { t, q -> t.title.containsIgnoringCase(q) }
        assertEquals(source.value, narrowed.value, "the value read before anything collects the list")
    }

    @Test
    fun aQueryNarrowsBothHalvesOfASplitListAndItFollowsItsSource() = runBlocking<Unit> {
        val source = MutableStateFlow(AvailableSplit(listOf(track("up:1"), track("up:2")), listOf(track("down:1"), track("down:2"))))
        val filter = ListFilter(scope)
        val narrowed = filter.narrowSplit(source) { t, q -> t.title.containsIgnoringCase(q) }
        suspend fun next(ok: (AvailableSplit<Track>) -> Boolean) = withTimeout(5_000) { narrowed.first(ok) }

        filter.set("2")
        val two = next { it.playable.size + it.unavailable.size == 2 }
        assertEquals(listOf("up:2"), two.playable.map { it.id })
        assertEquals(listOf("down:2"), two.unavailable.map { it.id })

        source.value = AvailableSplit(listOf(track("up:2"), track("down:2")), emptyList())
        val back = next { it.unavailable.isEmpty() }
        assertEquals(listOf("up:2", "down:2"), back.playable.map { it.id })

        filter.set("")
        assertEquals(2, next { it.playable.size == 2 }.playable.size)
    }

    @Test
    fun aWarmedFlowIsNotLoadedUntilItsSourceHasAnsweredOnce() {
        val flow = WarmedFlow(emptyList<String>())
        assertFalse(flow.loaded.value, "still only the default")
        flow.set(emptyList())
        assertTrue(flow.loaded.value, "answered, and the answer was: nothing")
    }

    @Test
    fun aWarmedSplitIsNotLoadedUntilTheLibraryAndTheServerPrefsHaveAnswered() = runBlocking<Unit> {
        // The prefs stay at their defaults: only whether they have answered matters here.
        AppServerPrefs.servers.set(AppServerPrefs.servers.value.value)
        AppServerPrefs.enabledServerIds.set(AppServerPrefs.enabledServerIds.value.value)
        AppServerPrefs.lastSyncedAt.set(AppServerPrefs.lastSyncedAt.value.value)
        AppServerPrefs.isConfigured.set(AppServerPrefs.isConfigured.value.value)
        AppAvailability.now.set(availability(down = emptySet()))
        val libraryLoaded = MutableStateFlow(false)
        val split = AppAvailability.split(MutableStateFlow(emptyList<Track>()), libraryLoaded, ListAvailability::songs)

        assertFalse(withTimeout(5_000) { split.first() }.loaded, "an empty list whose library has not answered is not empty yet")
        libraryLoaded.value = true
        val answered = withTimeout(5_000) { split.first { it.loaded } }
        assertTrue(answered.isEmpty && answered.loaded, "now it is empty for real")
    }

    @Test
    fun aQueryKeepsASplitListNotLoaded() = runBlocking<Unit> {
        val source = MutableStateFlow(AvailableSplit.notLoaded<Track>())
        val filter = ListFilter(scope)
        val narrowed = filter.narrowSplit(source) { t, q -> t.title.containsIgnoringCase(q) }
        assertFalse(narrowed.value.loaded)
        filter.set("x")
        assertFalse(withTimeout(5_000) { narrowed.first() }.loaded)
    }
}

