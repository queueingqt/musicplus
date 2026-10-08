package com.musicplus.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every capability has a way of being found out on each backend: a new one added without a probe would only fail at runtime otherwise. */
class CapabilityProbesTest {
    @Test
    fun subsonicHasAProbeForEveryCapability() {
        assertEquals(Capability.entries.toSet(), SubsonicApi.PROBES.keys)
    }

    @Test
    fun jellyfinAnswersForEveryCapabilityWithoutAskingTheServer() = runBlocking<Unit> {
        val api = JellyfinApi("jf", JellyfinClient(JellyfinConfig("http://127.0.0.1:1", "token", "user"), {}))
        val found = api.probeCapabilities()
        assertEquals(Capability.entries.toSet(), found.keys)
        assertEquals(Support.NO, found[Capability.SCROBBLE], "Jellyfin has no scrobble.view relay; its own reporting is a play reporter")
        assertEquals(Support.YES, found[Capability.STAR])
    }

    @Test
    fun onlyJellyfinOffersAPlayReporterAndOnlySubsonicAScrobbleTarget() {
        val jellyfin = JellyfinApi("jf", JellyfinClient(JellyfinConfig("http://127.0.0.1:1", "token", "user"), {}))
        assertEquals(true, jellyfin.playReporter != null)
        assertEquals(true, jellyfin.scrobbler == null)
        val subsonic = SubsonicApi("sub", SubsonicClient(ServerConfig("http://127.0.0.1:1", "u", "p"), {}), null)
        assertEquals(true, subsonic.scrobbler != null)
        assertEquals(true, subsonic.playReporter == null)
    }
}
