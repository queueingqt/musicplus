package com.musicplus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The real lookup, through a fake adapter factory and fake saved servers: routing by owner, building once, and rebuilding when asked. */
class ApiHolderTest {
    private fun profile(id: String, kind: ServerKind = ServerKind.SUBSONIC) =
        ServerProfile(id = id, name = id, baseUrl = "http://$id", username = "u", password = "p", kind = kind)

    private class Profiles(val all: List<ServerProfile>) : ServerProfiles {
        override val servers: Flow<List<ServerProfile>> = flowOf(all)
        override val activeProfile: Flow<ServerProfile?> = flowOf(all.firstOrNull())
    }

    private val built = mutableListOf<String>()
    private var active: String? = "one"

    private fun holder(vararg ids: String) = ApiHolder(
        profiles = Profiles(ids.map { profile(it) }),
        adapterFor = { p, _, _ -> built += p.id; StubMusicApi(p.id) },
        activeServerId = { active },
    )

    @Test
    fun anIdIsRoutedToTheServerThatOwnsIt() = runBlocking<Unit> {
        val apis = holder("one", "two")
        assertEquals("one", apis.forId("one:abc")!!.serverId)
        assertEquals("two", apis.forId("two:abc")!!.serverId)
    }

    @Test
    fun anApiIsBuiltOnceAndReused() = runBlocking<Unit> {
        val apis = holder("one", "two")
        val first = apis.forId("two:a")
        assertSame(first, apis.forId("two:b"))
        assertSame(first, apis.forServer("two"))
        assertEquals(listOf("two"), built.toList(), "only the server that was asked for was built")
    }

    @Test
    fun anIdThatWasNeverScopedFallsBackToTheActiveServer() = runBlocking<Unit> {
        val apis = holder("one", "two")
        assertEquals("one", apis.forId("no-scope")!!.serverId)
    }

    @Test
    fun aServerThatIsNotSavedHasNoApi() = runBlocking<Unit> {
        assertNull(holder("one").forId("ghost:abc"))
        assertNull(holder("one").forServer("ghost"))
    }

    @Test
    fun onlyAnApiThatHasBeenBuiltCanBePeekedAt() = runBlocking<Unit> {
        val apis = holder("one", "two")
        assertNull(apis.peekFor("two:abc"))
        assertNull(apis.peek())
        apis.forId("two:abc")
        assertNotNull(apis.peekFor("two:abc"))
        apis.get()
        assertNotNull(apis.peek())
    }

    @Test
    fun forgettingOrEditingAServerMakesTheNextUseBuildItAgainAndLeavesTheOthers() = runBlocking<Unit> {
        val apis = holder("one", "two")
        val before = apis.forServer("two")
        val other = apis.forServer("one")
        apis.forget("two")
        assertNotSame(before, apis.forServer("two"))
        val edited = apis.forServer("two")
        apis.edited("two")
        assertNotSame(edited, apis.forServer("two"))
        assertSame(other, apis.forServer("one"), "an edit of one server does not rebuild the others")
    }

    @Test
    fun theFactoryIsToldWhichServerItIsBuildingFor() = runBlocking<Unit> {
        val seen = mutableListOf<ServerKind>()
        val apis = ApiHolder(Profiles(listOf(profile("j", ServerKind.JELLYFIN))), { p, _, _ -> seen += p.kind; StubMusicApi(p.id) }, { null })
        apis.forServer("j")
        assertEquals(ServerKind.JELLYFIN, seen.single())
    }
}
