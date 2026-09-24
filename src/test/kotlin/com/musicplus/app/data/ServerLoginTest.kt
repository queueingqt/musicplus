package com.musicplus.app.data

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Both kinds of server through one interface: which answer means "wrong password" and which means "could not ask", and what saving needs. */
class ServerLoginTest {
    private val asked = mutableListOf<String>()
    private var subsonic: Result<Unit> = Result.success(Unit)
    private var jellyfin: Result<JellyfinAuthResult> = Result.success(JellyfinAuthResult(User = JellyfinUser(Id = "u1"), AccessToken = "tok"))

    private val login = ServerLogin(
        jellyfinDeviceId = { "device" },
        appVersion = "1.2",
        checkSubsonic = { asked += "subsonic ${it.baseUrl} ${it.username}"; subsonic },
        signInJellyfin = { url, user, _, device, version -> asked += "jellyfin $url $user $device $version"; jellyfin },
    )

    private fun draft(kind: ServerKind) = ServerDraft(kind, "Home", "http://h/", "me", "pw")

    @Test
    fun aSubsonicServerThatTakesTheLoginIsAcceptedAndAskedByItsAddressWithoutTheTrailingSlash() = runBlocking<Unit> {
        assertEquals(LoginResult.Accepted(), login.test(draft(ServerKind.SUBSONIC)))
        assertEquals(listOf("subsonic http://h me"), asked)
    }

    @Test
    fun aSubsonicServerSayingNoIsRejectedWithItsReason() = runBlocking<Unit> {
        subsonic = Result.failure(SubsonicApiException(40, "Wrong username or password"))
        assertEquals(LoginResult.Rejected("Wrong username or password"), login.test(draft(ServerKind.SUBSONIC)))
    }

    @Test
    fun aSubsonicServerThatCannotBeReachedFailsRatherThanBeingRejected() = runBlocking<Unit> {
        val error = IOException("no route")
        subsonic = Result.failure(error)
        assertEquals(LoginResult.Failed(error), login.test(draft(ServerKind.SUBSONIC)))
    }

    @Test
    fun aJellyfinLoginCarriesItsTokenAndTheAppIsIdentifiedByDeviceAndVersion() = runBlocking<Unit> {
        val result = login.test(draft(ServerKind.JELLYFIN))
        assertEquals("tok", assertIs<LoginResult.Accepted>(result).auth?.AccessToken)
        assertEquals(listOf("jellyfin http://h me device 1.2"), asked)
    }

    @Test
    fun aJellyfinServerSayingNoIsRejectedAndOneThatCannotBeReachedFails() = runBlocking<Unit> {
        jellyfin = Result.failure(JellyfinApiException(401, "Invalid username or password"))
        assertEquals(LoginResult.Rejected("Invalid username or password"), login.test(draft(ServerKind.JELLYFIN)))
        jellyfin = Result.failure(IOException("down"))
        assertIs<LoginResult.Failed>(login.test(draft(ServerKind.JELLYFIN)))
    }

    @Test
    fun savingAJellyfinServerSignsInButSavingASubsonicOneAsksNothing() = runBlocking<Unit> {
        assertEquals(LoginResult.Accepted(), login.forSaving(draft(ServerKind.SUBSONIC)))
        assertEquals(emptyList(), asked, "a Subsonic server is saved as typed, reachable or not")
        assertIs<LoginResult.Accepted>(login.forSaving(draft(ServerKind.JELLYFIN)))
        assertEquals(1, asked.size)
    }

    @Test
    fun aDraftBecomesAProfileWithAFallbackNameAndTheJellyfinSession() {
        val profile = ServerDraft(ServerKind.JELLYFIN, "", "http://h/", "me", "pw")
            .toProfile("id1", JellyfinAuthResult(User = JellyfinUser(Id = "u1"), AccessToken = "tok"))
        assertEquals("http://h/", profile.name)
        assertEquals("http://h", profile.baseUrl)
        assertEquals("tok", profile.jellyfinAccessToken)
        assertEquals("u1", profile.jellyfinUserId)
        assertNull(ServerDraft(ServerKind.SUBSONIC, "S", "http://h", "me", "pw").toProfile("id2").jellyfinAccessToken)
    }
}
