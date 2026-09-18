package com.musicplus.app.data

import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Composition root. A single process-lifetime instance, built synchronously the
 * first time any screen asks for it — `LightScreen.createViewModel()` isn't a
 * suspend function, so nothing here can await an async read (see
 * [SubsonicApiHolder] for how the server-dependent pieces defer past that).
 */
object AppGraph {
    class Graph(
        val serverConfigRepository: ServerConfigRepository,
        val appSettingsRepository: AppSettingsRepository,
        val apiHolder: SubsonicApiHolder,
        val database: MusicPlusDatabase,
        val libraryRepository: LibraryRepository,
        val playlistRepository: PlaylistRepository,
        val downloadRepository: DownloadRepository,
        val albumArtRepository: AlbumArtRepository,
        val connectivity: LightConnectivity,
    )

    @Volatile private var instance: Graph? = null

    // Process-lifetime, not tied to any screen's own viewModelScope — needed so
    // debugLoggingEnabled keeps being observed (and AppLogger kept in sync) no
    // matter which screen is currently on top, including screens that never
    // touch AppSettingsRepository themselves.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun from(lightContext: SealedLightContext): Graph =
        instance ?: synchronized(this) {
            instance ?: build(lightContext).also { instance = it }
        }

    /** Call after SettingsScreen saves a new server URL/credentials so the next API call re-resolves. */
    fun invalidateApi() {
        instance?.apiHolder?.invalidate()
    }

    private fun build(lightContext: SealedLightContext): Graph {
        // First thing any screen touches (see class doc) — as early as this
        // process-lifetime singleton can install the crash handler.
        AppLogger.init(lightContext.filesDir)

        val serverConfigRepository = ServerConfigRepository(lightContext.dataStore)
        val appSettingsRepository = AppSettingsRepository(lightContext.dataStore)
        appScope.launch {
            appSettingsRepository.debugLoggingEnabled.collect { AppLogger.setEnabled(it) }
        }
        appScope.launch {
            appSettingsRepository.hapticFeedbackEnabled.collect { AppHaptics.setEnabled(it) }
        }
        appScope.launch {
            appSettingsRepository.showAlbumArtwork.collect { AppDisplayPrefs.setShowAlbumArtwork(it) }
        }
        val apiHolder = SubsonicApiHolder(serverConfigRepository)
        val database = MusicPlusDatabase.create(lightContext)
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
        val albumArtRepository = AlbumArtRepository(
            apiHolder = apiHolder,
            filesDir = lightContext.filesDir,
        )
        return Graph(
            serverConfigRepository = serverConfigRepository,
            appSettingsRepository = appSettingsRepository,
            apiHolder = apiHolder,
            database = database,
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
            downloadRepository = downloadRepository,
            albumArtRepository = albumArtRepository,
            connectivity = connectivity,
        )
    }
}
