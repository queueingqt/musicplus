package com.musicplus.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules a server's standing answers, on plain state: no globals to set up. */
class ServerStandingTest {
    private fun profile(id: String, name: String) = ServerProfile(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    private val bandcamp = ServerCapabilities(star = false, scrobble = false, playlistWrite = false)
    private val navidrome = ServerCapabilities(star = true, scrobble = true, playlistWrite = true)

    private fun standing(
        enabled: Set<String> = setOf("nav", "bc"),
        unreachable: Set<String> = emptySet(),
        capabilities: Map<String, ServerCapabilities> = mapOf("nav" to navidrome, "bc" to bandcamp),
        removed: List<RemovedServer> = emptyList(),
    ) = ServerStanding(listOf(profile("nav", "Navidrome"), profile("bc", "Bandcamp"), profile("off", "Off")), enabled, unreachable, capabilities, removed)

    @Test
    fun aServerIsUsableWhenItIsOnAndWasReached() {
        val s = standing(unreachable = setOf("bc"))
        assertTrue(s.usable("nav"))
        assertFalse(s.usable("bc"), "on, but could not be reached")
        assertFalse(s.usable("off"), "switched off")
        assertTrue(s.usable(null), "no server to wait for")
    }

    @Test
    fun nothingIsGreyedOutBeforeAnyServerIsKnown() {
        assertTrue(ServerStanding().usable("nav"), "the saved list has not been read yet")
    }

    @Test
    fun whatIsNotYetKnownCountsAsWhatTheServerCan() {
        val s = standing(capabilities = emptyMap())
        assertTrue(s.can("nav", Capability.STAR))
        assertTrue(s.keepsFavorites("nav"))
        assertTrue(s.canHoldPlaylists("nav"))
    }

    @Test
    fun aServerKnownNotToKeepFavoritesKeepsHeartsOnThePhone() {
        val s = standing()
        assertTrue(s.keepsFavorites("nav"))
        assertFalse(s.keepsFavorites("bc"))
        assertTrue(s.cannot("bc", Capability.STAR))
        assertTrue(s.keepsFavorites(null))
    }

    @Test
    fun aPlaylistCanLiveOnAnOnServerThatKeepsThemNeverOnThePhone() {
        val s = standing(enabled = setOf("nav", "bc"))
        assertTrue(s.canHoldPlaylists("nav"))
        assertFalse(s.canHoldPlaylists("bc"), "cannot keep playlists")
        assertFalse(s.canHoldPlaylists("off"), "off")
        assertFalse(s.canHoldPlaylists(ServerScope.PHONE))
        assertFalse(s.canHoldPlaylists(null))
    }

    @Test
    fun onlyAnOnServerThatOffersItCountsAListen() {
        val s = standing()
        assertTrue(s.canScrobble("nav"))
        assertFalse(s.canScrobble("bc"), "offers none")
        assertFalse(standing(enabled = setOf("bc")).canScrobble("nav"), "switched off")
        assertFalse(s.canScrobble(null))
    }

    @Test
    fun aServerIsNamedByItsSavedNameOrAsRemovedOrAsThePhone() {
        val s = standing(removed = listOf(RemovedServer("gone", "Old", "http://old", "u")))
        assertEquals("Navidrome", s.nameOf("nav"))
        assertEquals("Old (removed)", s.nameOf("gone"))
        assertEquals(ServerScope.PHONE_ONLY_LABEL, s.nameOf(ServerScope.PHONE))
        assertNull(s.nameOf("nobody"))
        assertNull(s.nameOf(null))
    }
}
