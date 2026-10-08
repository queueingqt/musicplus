package com.musicplus.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A server's life, with fakes for where servers are saved, what holds state for one, and what one leaves behind: the order things happen in, and that nothing is forgotten. */
class ServerLifecycleTest {
    private val events = CopyOnWriteArrayList<String>()

    private class Registry(private val events: MutableList<String>) : ServerRegistry {
        val profiles = mutableListOf<ServerProfile>()
        val on = mutableSetOf<String>()
        val removed = mutableListOf<RemovedServer>()

        override suspend fun saved() = SavedServers(profiles.toList(), profiles.firstOrNull { it.id in on }?.id, on.toSet())

        override suspend fun addOrUpdate(profile: ServerProfile) {
            events += "registry.addOrUpdate ${profile.id}"
            val i = profiles.indexOfFirst { it.id == profile.id }
            if (i >= 0) profiles[i] = profile else { profiles += profile; on += profile.id }
            removed.removeAll { it.id == profile.id }
        }

        override suspend fun setEnabled(id: String, on: Boolean) {
            events += "registry.setEnabled $id $on"
            if (on) this.on += id else this.on -= id
        }

        override suspend fun remove(id: String, keptDownloadsAs: RemovedServer?) {
            events += "registry.remove $id kept=${keptDownloadsAs != null}"
            profiles.removeAll { it.id == id }
            on -= id
            keptDownloadsAs?.let { removed += it }
        }

        override suspend fun forgetRemoved(id: String) {
            events += "registry.forgetRemoved $id"
            removed.removeAll { it.id == id }
        }

        override suspend fun findRemoved(baseUrl: String, username: String) =
            removed.find { it.baseUrl == baseUrl && it.username == username }
    }

    private class Holder(private val name: String, private val events: MutableList<String>) : PerServerState {
        override suspend fun forget(serverId: String) { events += "forget $name $serverId" }
        override fun edited(serverId: String) { events += "edited $name $serverId" }
    }

    private class Leftovers(private val events: MutableList<String>, var finished: Int = 0) : ServerLeftovers {
        override val downloadSummaries: Flow<Map<String, DownloadSummary>> = emptyFlow()
        override suspend fun finishedDownloads(serverId: String) = finished
        override suspend fun clear(serverId: String, keepDownloads: Boolean) { events += "leftovers.clear $serverId keep=$keepDownloads" }
        override suspend fun clearKept(serverId: String) { events += "leftovers.clearKept $serverId" }
    }

    private val registry = Registry(events)
    private val leftovers = Leftovers(events)
    private val work = ServerWork()
    private val announced = CopyOnWriteArrayList<Pair<List<String>, Set<String>>>()
    private var subsonic: Result<Unit> = Result.success(Unit)
    private var jellyfin: Result<JellyfinAuthResult> = Result.success(JellyfinAuthResult(User = JellyfinUser(Id = "u"), AccessToken = "tok"))
    private var ids = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lifecycle = ServerLifecycle(
        scope = scope,
        registry = registry,
        login = ServerLogin(
            jellyfinDeviceId = { "d" },
            appVersion = "1",
            checkSubsonic = { subsonic },
            signInJellyfin = { _, _, _, _, _ -> jellyfin },
        ),
        work = work,
        holders = listOf(Holder("api", events), Holder("sync", events), Holder("caps", events), Holder("reach", events)),
        leftovers = leftovers,
        announce = { saved -> events += "announce"; announced += saved.servers.map { it.id } to saved.enabledIds },
        newId = { "new${++ids}" },
    )

    private fun draft(kind: ServerKind = ServerKind.SUBSONIC, name: String = "Home", url: String = "http://h", user: String = "me") =
        ServerDraft(kind, name, url, user, "pw")

    @Test
    fun aNewServerIsSavedSwitchedOnAnnouncedAndNothingIsToldItWasEdited() = runBlocking<Unit> {
        assertEquals(SaveOutcome.Saved, lifecycle.save(null, draft()))
        assertEquals(listOf("new1"), registry.saved().servers.map { it.id })
        assertEquals(setOf("new1"), registry.saved().enabledIds)
        assertEquals(listOf(listOf("new1") to setOf("new1")), announced.toList())
        assertTrue(events.none { it.startsWith("edited") })
    }

    @Test
    fun aServerThatDoesNotTakeAJellyfinLoginIsNotSavedAndNothingIsAnnounced() = runBlocking<Unit> {
        jellyfin = Result.failure(JellyfinApiException(401, "no"))
        val outcome = lifecycle.save(null, draft(ServerKind.JELLYFIN))
        assertEquals(SaveOutcome.Refused(LoginResult.Rejected("no")), outcome)
        assertTrue(registry.profiles.isEmpty() && announced.isEmpty())

        val error = IOException("down")
        jellyfin = Result.failure(error)
        assertEquals(SaveOutcome.Refused(LoginResult.Failed(error)), lifecycle.save(null, draft(ServerKind.JELLYFIN)))
        assertTrue(registry.profiles.isEmpty())
    }

    @Test
    fun aSavedJellyfinServerKeepsItsAccessToken() = runBlocking<Unit> {
        lifecycle.save(null, draft(ServerKind.JELLYFIN))
        assertEquals("tok", registry.profiles.single().jellyfinAccessToken)
    }

    @Test
    fun aSubsonicServerIsSavedEvenIfItCannotBeReachedRightNow() = runBlocking<Unit> {
        subsonic = Result.failure(IOException("no route"))
        assertEquals(SaveOutcome.Saved, lifecycle.save(null, draft()))
    }

    @Test
    fun anEditKeepsTheIdChangesTheProfileAndTellsEveryHolderToDropWhatItBuiltFromTheOldOne() = runBlocking<Unit> {
        lifecycle.save(null, draft(name = "Home"))
        events.clear()
        lifecycle.save("new1", draft(name = "Renamed", url = "http://other"))
        assertEquals("Renamed", registry.profiles.single().name)
        assertEquals("http://other", registry.profiles.single().baseUrl)
        assertEquals(listOf("api", "sync", "caps", "reach").map { "edited $it new1" }, events.filter { it.startsWith("edited") })
        assertEquals(2, announced.size)
    }

    @Test
    fun aNewLoginForARemovedServersAddressAndUserTakesItsIdBack() = runBlocking<Unit> {
        registry.removed += RemovedServer("old-id", "Old", "http://h", "me")
        lifecycle.save(null, draft(url = "http://h/"))
        assertEquals(listOf("old-id"), registry.profiles.map { it.id })
        assertTrue(registry.removed.isEmpty(), "the kept-downloads record is picked up")
    }

    @Test
    fun switchingAServerOffAndOnIsAnnouncedEachTime() = runBlocking<Unit> {
        lifecycle.save(null, draft())
        lifecycle.setEnabled("new1", false)
        lifecycle.setEnabled("new1", true)
        assertEquals(listOf(setOf("new1"), emptySet(), setOf("new1")), announced.map { it.second })
    }

    @Test
    fun removingForgetsTheLoginFirstThenStopsWhatIsRunningThenForgetsInEveryHolderThenDeletesWhatItLeft() = runBlocking<Unit> {
        lifecycle.save(null, draft())
        events.clear()
        val started = CompletableDeferred<Unit>()
        val inFlight = scope.async {
            work.run("new1") {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    events += "in-flight work stopped"
                }
            }
        }
        started.await()

        lifecycle.remove("new1", keepDownloads = false).join()

        assertEquals(
            listOf(
                "registry.remove new1 kept=false",
                "announce",
                "in-flight work stopped",
                "forget api new1", "forget sync new1", "forget caps new1", "forget reach new1",
                "leftovers.clear new1 keep=false",
            ),
            events.toList(),
        )
        assertEquals(null, inFlight.await())
        assertTrue(registry.profiles.isEmpty())
        assertEquals(listOf(emptyList<String>() to emptySet<String>()), announced.drop(1))
    }

    @Test
    fun removingWithDownloadsKeptLeavesARecordForThemAndDeletesTheRest() = runBlocking<Unit> {
        lifecycle.save(null, draft(name = "Home", url = "http://h", user = "me"))
        leftovers.finished = 5
        lifecycle.remove("new1", keepDownloads = true).join()
        assertEquals(listOf(RemovedServer("new1", "Home", "http://h", "me")), registry.removed)
        assertTrue("leftovers.clear new1 keep=true" in events)
    }

    @Test
    fun keepingDownloadsWhenThereAreNoneKeepsNothing() = runBlocking<Unit> {
        lifecycle.save(null, draft())
        leftovers.finished = 0
        lifecycle.remove("new1", keepDownloads = true).join()
        assertTrue(registry.removed.isEmpty())
        assertTrue("leftovers.clear new1 keep=false" in events)
    }

    @Test
    fun deletingKeptDownloadsDeletesThemAndTheRecord() = runBlocking<Unit> {
        registry.removed += RemovedServer("gone", "Gone", "http://g", "me")
        lifecycle.deleteKeptDownloads("gone").join()
        assertEquals(listOf("leftovers.clearKept gone", "registry.forgetRemoved gone"), events.toList())
        assertTrue(registry.removed.isEmpty())
    }

    @Test
    fun aHolderAddedToTheListIsForgottenLikeTheOthers() = runBlocking<Unit> {
        val extra = Holder("fifth", events)
        val withFifth = ServerLifecycle(
            scope = scope, registry = registry, login = ServerLogin({ "d" }, "1"), work = work,
            holders = listOf(extra), leftovers = leftovers, announce = {},
        )
        registry.profiles += ServerProfile("s", "S", "http://s", "u", "p")
        registry.on += "s"
        withFifth.remove("s", keepDownloads = false).join()
        assertTrue("forget fifth s" in events)
        assertIs<Registry>(registry)
    }
}
