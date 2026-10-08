package com.musicplus.app.data

/** What a person typed into the add/edit server form. */
data class ServerDraft(
    val kind: ServerKind,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    /** What is saved for this draft under [id]: a Jellyfin profile also holds the [auth] its login returned. */
    fun toProfile(id: String, auth: JellyfinAuthResult? = null) = ServerProfile(
        id = id,
        name = name.ifBlank { baseUrl },
        baseUrl = baseUrl.trimEnd('/'),
        username = username,
        password = password,
        kind = kind,
        jellyfinAccessToken = auth?.AccessToken,
        jellyfinUserId = auth?.User?.Id,
    )
}

/** What asking a server to accept a login came to. */
sealed interface LoginResult {
    /** The server took the login. [auth] is what Jellyfin handed back (null for a Subsonic server, which is asked again on every request). */
    data class Accepted(val auth: JellyfinAuthResult? = null) : LoginResult

    /** The server answered and said no (a wrong password, an unknown user). */
    data class Rejected(val reason: String) : LoginResult

    /** The server could not be asked at all (no route, a bad address, a reply that is not from this kind of server). */
    data class Failed(val error: Throwable) : LoginResult
}

/**
 * Finds out whether a server takes a login, for either kind of server, so the add/edit screen keeps only its form and never builds a
 * client or knows which exception a backend throws to say "wrong password".
 */
class ServerLogin(
    private val jellyfinDeviceId: suspend () -> String,
    private val appVersion: String,
    private val checkSubsonic: suspend (ServerConfig) -> Result<Unit> = { SubsonicClient(it).checkLogin() },
    private val signInJellyfin: suspend (baseUrl: String, username: String, password: String, deviceId: String, appVersion: String) -> Result<JellyfinAuthResult> =
        ::authenticateJellyfin,
) {
    /** Asks the server. A Subsonic one is asked with [SubsonicClient.checkLogin], not a ping: a ping says OK to a wrong password on some servers (Bandcamp's). */
    suspend fun test(draft: ServerDraft): LoginResult {
        val baseUrl = draft.baseUrl.trimEnd('/')
        return when (draft.kind) {
            ServerKind.SUBSONIC -> checkSubsonic(ServerConfig(baseUrl, draft.username, draft.password)).fold(
                onSuccess = { LoginResult.Accepted() },
                onFailure = { if (it is SubsonicApiException) LoginResult.Rejected(it.message.orEmpty()) else LoginResult.Failed(it) },
            )
            ServerKind.JELLYFIN -> signInJellyfin(baseUrl, draft.username, draft.password, jellyfinDeviceId(), appVersion).fold(
                onSuccess = { LoginResult.Accepted(it) },
                onFailure = { if (it is JellyfinApiException) LoginResult.Rejected(it.message.orEmpty()) else LoginResult.Failed(it) },
            )
        }
    }

    /**
     * What saving needs. A Jellyfin profile without its access token is not something the rest of the app can use, so it is signed in
     * here even if the person never tapped "Test connection"; a Subsonic one is saved as typed, whether or not the server can be
     * reached right now.
     */
    suspend fun forSaving(draft: ServerDraft): LoginResult =
        if (draft.kind == ServerKind.JELLYFIN) test(draft) else LoginResult.Accepted()
}
