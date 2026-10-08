package com.musicplus.app

import com.musicplus.app.data.DownloadSummary
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a server's menu offers and says, without a screen. The confirm rows' taps open a dialog, so those are checked by their wording and order. */
class ServerActionsTest {
    private val none: DownloadSummary? = null
    private val one = DownloadSummary(songs = 1, bytes = 5_000_000)
    private val many = DownloadSummary(songs = 12, bytes = 300_000_000)

    @Test
    fun aServerWithNoDownloadsOffersOneDelete() {
        val items = serverMenuItems("NASTY", isOn = true, downloads = none, setEnabled = {}, remove = {})
        assertEquals(listOf("Turn off", "Delete"), items.map { it.label })
        assertEquals(listOf("Delete"), serverRemovalItems("NASTY", DownloadSummary(0, 0)) { }.map { it.label }, "an empty summary counts as none")
    }

    @Test
    fun aServerWithDownloadsOffersKeepOrDeleteThem() {
        val items = serverRemovalItems("NASTY", many) { }
        assertEquals(listOf("Delete, keep downloads", "Delete with downloads"), items.map { it.label })
    }

    @Test
    fun aServerThatIsOffOffersTurnOn() {
        assertEquals("Turn on", serverToggleItem(isOn = false) { }.label)
        assertEquals("Turn off", serverToggleItem(isOn = true) { }.label)
        assertEquals(LightIcons.SELECT_ON, serverToggleItem(isOn = false) { }.icon)
    }

    @Test
    fun turningAServerOnOrOffCallsBackAndFlipsTheRowInPlace() = runBlocking<Unit> {
        val calls = mutableListOf<Boolean>()
        val on = serverToggleItem(isOn = true) { calls += it }
        val perform = assertIs<ActionMenuSelection.Perform>(on.onSelect)
        val flipped = perform.action()!!
        assertEquals(listOf(false), calls, "asked to turn it off")
        assertEquals("Turn on", flipped.label)
        assertEquals(on.key, flipped.key, "the same row, so a live update or a second tap still finds it")
        val back = assertIs<ActionMenuSelection.Perform>(flipped.onSelect).action()!!
        assertEquals(listOf(false, true), calls)
        assertEquals("Turn off", back.label)
    }

    @Test
    fun theKeepAndDeleteMessagesNameWhatIsOnThePhone() {
        assertEquals("Its library leaves this phone. The 12 songs (300 MB) you downloaded stay listed and playable.", keepDownloadsMessage(many))
        assertTrue(keepDownloadsMessage(one).contains("1 song") && keepDownloadsMessage(one).contains(" stays "), keepDownloadsMessage(one))
        assertEquals("This also removes 12 songs (300 MB) from this phone. It can't be undone.", deleteWithDownloadsMessage(many))
    }

    @Test
    fun aRemovedServersDeleteDownloadsSaysWhatIsLeftWhenKnown() {
        assertEquals("Removes 12 songs (300 MB) that came from \"Old\" from this phone. This can't be undone.", deleteKeptDownloadsMessage("Old", many))
        assertEquals("Removes the songs that came from \"Old\" from this phone. This can't be undone.", deleteKeptDownloadsMessage("Old", null))
        assertEquals(listOf("Delete downloads"), removedServerItems("Old", many) { }.map { it.label })
    }
}
