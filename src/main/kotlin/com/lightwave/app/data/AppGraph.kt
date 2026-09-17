package com.lightwave.app.data

import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.SealedLightContext

/**
 * Composition root. A single process-lifetime instance, built synchronously the
 * first time any screen asks for it — `LightScreen.createViewModel()` isn't a
 * suspend function, so nothing here can await an async read (see
 * [SubsonicApiHolder] for how the server-dependent pieces defer past that).
 */
object AppGraph {
    class Graph(
        val serverConfigRepository: ServerConfigRepository,
        val apiHolder: SubsonicApiHolder,
        val database: LightwaveDatabase,
        val libraryRepository: LibraryRepository,
        val playlistRepository: PlaylistRepository,
        val downloadRepository: DownloadRepository,
        val connectivity: LightConnectivity,
    )

    @Volatile private var instance: Graph? = null

    fun from(lightContext: SealedLightContext): Graph =
        instance ?: synchronized(this) {
            instance ?: build(lightContext).also { instance = it }
        }

    /** Call after SettingsScreen saves a new server URL/credentials so the next API call re-resolves. */
    fun invalidateApi() {
        instance?.apiHolder?.invalidate()
    }

    private fun build(lightContext: SealedLightContext): Graph {
        val serverConfigRepository = ServerConfigRepository(lightContext.dataStore)
        val apiHolder = SubsonicApiHolder(serverConfigRepository)
        val database = LightwaveDatabase.create(lightContext)
        // `SealedLightContext.androidContext` is internal to :sdk:client (not visible
        // to a consumer module like this one) — it already exposes a `connectivity`
        // property built from it for exactly this reason.
        val connectivity = lightContext.connectivity
        val libraryRepository = LibraryRepository(
            apiHolder = apiHolder,
            artistDao = database.artistDao(),
            albumDao = database.albumDao(),
            trackDao = database.trackDao(),
            connectivity = connectivity,
        )
        val playlistRepository = PlaylistRepository(
            apiHolder = apiHolder,
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            connectivity = connectivity,
        )
        val downloadRepository = DownloadRepository(
            downloadDao = database.downloadDao(),
            trackDao = database.trackDao(),
        )
        return Graph(
            serverConfigRepository = serverConfigRepository,
            apiHolder = apiHolder,
            database = database,
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
            downloadRepository = downloadRepository,
            connectivity = connectivity,
        )
    }
}
