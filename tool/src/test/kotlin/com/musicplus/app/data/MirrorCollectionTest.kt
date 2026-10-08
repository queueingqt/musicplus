package com.musicplus.app.data

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** One collection mirrored from a server, through the module's interface: a stub api, an in-memory table, and the guards every collection needs. */
class MirrorCollectionTest {
    private data class Row(val id: String, val name: String, val starred: Boolean = false)

    private class Table(initial: List<Row>) {
        val rows = initial.associateBy { it.id }.toMutableMap()
        val storedBatches = mutableListOf<List<Row>>()
        val deleted = mutableListOf<String>()
    }

    private fun spec(table: Table, hearts: Boolean = true) = MirrorSpec(
        label = "rows",
        idOf = Row::id,
        cachedFor = { table.rows.values.toList() },
        store = { batch, stars ->
            table.storedBatches += batch
            // What the real stores do: a row the phone's own heart wins for keeps the phone's heart.
            for (row in batch) table.rows[row.id] = if (hearts && stars.wins(row.id)) row.copy(starred = table.rows[row.id]?.starred ?: row.starred) else row
        },
        delete = { ids -> table.deleted += ids; ids.forEach(table.rows::remove) },
        starred = if (hearts) Starred(Row::starred) { row, on -> row.copy(starred = on) } else null,
    )

    private val api = StubMusicApi("srv")
    private val noPending = LocalStars(emptySet()) { true }

    @Test
    fun aNewRowIsStoredAndAnUnchangedOneIsNot() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one")))
        mirrorCollection(api, { true }, spec(table), noPending, fetchAll = { it(listOf(Row("srv:1", "one"), Row("srv:2", "two"))) })
        assertEquals(listOf(listOf(Row("srv:2", "two"))), table.storedBatches, "only what changed is written")
    }

    @Test
    fun nothingIsStoredOrRemovedOnceTheServerIsNoLongerLive() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one")))
        mirrorCollection(api, { false }, spec(table), noPending, fetchAll = { it(listOf(Row("srv:9", "new"))) })
        assertTrue(table.storedBatches.isEmpty(), "a server removed mid-refresh must not get rows written")
        assertEquals(setOf("srv:1"), table.rows.keys)
    }

    @Test
    fun aServerThatListsNothingNeverEmptiesTheCache() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one"), Row("srv:2", "two")))
        mirrorCollection(api, { true }, spec(table), noPending, fetchAll = { it(emptyList()) })
        assertTrue(table.deleted.isEmpty())
        assertEquals(2, table.rows.size)
    }

    @Test
    fun aHeartTheServerDoesNotKnowYetIsNotOverwrittenByItsOlderAnswer() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one", starred = true)))
        val pending = LocalStars(setOf("srv:1")) { true }
        mirrorCollection(api, { true }, spec(table), pending, fetchAll = { it(listOf(Row("srv:1", "one renamed", starred = false))) })
        assertEquals(Row("srv:1", "one renamed", starred = true), table.rows["srv:1"], "the rename arrives, the heart stays")
    }

    @Test
    fun aCollectionWithoutHeartsTakesTheServersRowsAsTheyAre() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one", starred = true)))
        val pending = LocalStars(setOf("srv:1")) { true }
        mirrorCollection(api, { true }, spec(table, hearts = false), pending, fetchAll = { it(listOf(Row("srv:1", "one", starred = false))) })
        assertEquals(Row("srv:1", "one", starred = false), table.rows["srv:1"])
    }

    @Test
    fun aFailedListingLeavesTheCacheAsItWas() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one")))
        assertFailsWith<IOException> {
            mirrorCollection(api, { true }, spec(table), noPending, fetchAll = { throw IOException("offline") })
        }
        assertTrue(table.storedBatches.isEmpty() && table.deleted.isEmpty())
    }

    @Test
    fun aRowTheServerStoppedListingGoesAfterTheConfirmingPassUnlessItIsKept() = runBlocking {
        val table = Table(listOf(Row("srv:1", "one"), Row("srv:2", "two"), Row("srv:3", "three")))
        var passes = 0
        mirrorCollection(
            api, { true }, spec(table), noPending,
            fetchAll = { passes++; it(listOf(Row("srv:1", "one"))) },
            keep = { candidates -> candidates.filterTo(HashSet()) { it == "srv:3" } },
        )
        assertEquals(2, passes, "a second pass confirms before anything is removed")
        assertEquals(listOf("srv:2"), table.deleted)
        assertEquals(setOf("srv:1", "srv:3"), table.rows.keys)
    }
}
