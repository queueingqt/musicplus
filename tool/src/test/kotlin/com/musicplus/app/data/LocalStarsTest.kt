package com.musicplus.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the phone's own heart wins over the server's answer: one rule for every refresh (#70). */
class LocalStarsTest {
    private data class Row(val id: String, val starred: Boolean)

    private var keepsFavorites = true
    private val asked = mutableListOf<List<String>>()

    private fun stars(pending: Set<String> = emptySet()) = LocalStars(pending) { keepsFavorites }

    private suspend fun keep(stars: LocalStars, fetched: List<Row>, local: List<Row>) =
        stars.keeping(fetched, Row::id, Row::starred, { row, starred -> row.copy(starred = starred) }) { ids -> asked += ids; local.filter { it.id in ids } }

    @Test
    fun aServerThatKeepsFavoritesWithNothingPendingIsTheTruthForItsOwnRows() = runBlocking<Unit> {
        val fetched = listOf(Row("srv:a", true), Row("srv:b", false))
        val local = listOf(Row("srv:a", false), Row("srv:b", true))
        assertEquals(fetched, keep(stars(), fetched, local))
        assertTrue(asked.isEmpty(), "the phone's rows were not even read")
    }

    /** #70: opening an album (or an artist, or a playlist) used to overwrite a favourite toggled offline with the server's older `false`. */
    @Test
    fun aFavouriteToggledOfflineSurvivesOpeningTheDetailThatRefreshesIt() = runBlocking<Unit> {
        val fetchedByTheDetailRefresh = listOf(Row("srv:track", false), Row("srv:other", false))
        val phone = listOf(Row("srv:track", true), Row("srv:other", false))
        val written = keep(stars(pending = setOf("srv:track")), fetchedByTheDetailRefresh, phone)
        assertEquals(listOf(Row("srv:track", true), Row("srv:other", false)), written)
    }

    @Test
    fun anUnHeartMadeOfflineSurvivesToo() = runBlocking<Unit> {
        val written = keep(stars(pending = setOf("srv:a")), listOf(Row("srv:a", true)), listOf(Row("srv:a", false)))
        assertEquals(listOf(Row("srv:a", false)), written)
    }

    @Test
    fun aServerThatKeepsNoFavoritesNeverErasesAHeartMadeOnThePhone() = runBlocking<Unit> {
        keepsFavorites = false
        val written = keep(stars(), listOf(Row("srv:a", false), Row("srv:b", false)), listOf(Row("srv:a", true), Row("srv:b", false)))
        assertEquals(listOf(Row("srv:a", true), Row("srv:b", false)), written)
    }

    @Test
    fun aRowThePhoneDoesNotHaveIsLeftAsTheServerSaidIt() = runBlocking<Unit> {
        keepsFavorites = false
        val written = keep(stars(pending = setOf("srv:new")), listOf(Row("srv:new", true)), emptyList())
        assertEquals(listOf(Row("srv:new", true)), written)
    }

    @Test
    fun onlyTheRowsTheHeartCouldMatterForAreLookedUp() = runBlocking<Unit> {
        keep(stars(pending = setOf("srv:a")), listOf(Row("srv:a", false), Row("srv:b", false)), listOf(Row("srv:a", true)))
        assertEquals(listOf(listOf("srv:a")), asked)
    }

    @Test
    fun theRuleForOneId() {
        val stars = stars(pending = setOf("srv:pending"))
        assertTrue(stars.wins("srv:pending"))
        assertFalse(stars.wins("srv:other"))
        keepsFavorites = false
        assertTrue(stars.wins("srv:other"), "a server that keeps no favorites")
    }
}
