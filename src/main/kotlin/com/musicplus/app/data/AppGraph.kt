package com.musicplus.app.data

import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        val playbackStateRepository: PlaybackStateRepository,
        val apiHolder: SubsonicApiHolder,
        val database: MusicPlusDatabase,
        val libraryRepository: LibraryRepository,
        val playlistRepository: PlaylistRepository,
        val downloadRepository: DownloadRepository,
        val albumArtRepository: AlbumArtRepository,
        val lyricsRepository: LyricsRepository,
        val syncQueueRepository: SyncQueueRepository,
        val localDataRepository: LocalDataRepository,
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

    // Opts into MusicPlusDatabase.create() — see DatabaseFactoryAccess's doc for
    // why that function is opt-in-gated rather than just `internal`: this is the
    // one function in the whole tool meant to ever call it.
    @OptIn(DatabaseFactoryAccess::class)
    private fun build(lightContext: SealedLightContext): Graph {
        // First thing any screen touches (see class doc) — as early as this
        // process-lifetime singleton can install the crash handler.
        AppLogger.init(lightContext.filesDir)

        val serverConfigRepository = ServerConfigRepository(lightContext.dataStore)
        val appSettingsRepository = AppSettingsRepository(lightContext.dataStore)
        val playbackStateRepository = PlaybackStateRepository(lightContext.dataStore)
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
        val lyricsRepository = LyricsRepository(
            apiHolder = apiHolder,
            filesDir = lightContext.filesDir,
        )
        val syncQueueRepository = SyncQueueRepository(
            pendingMutationDao = database.pendingMutationDao(),
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
        )
        val localDataRepository = LocalDataRepository(
            database = database,
            playbackStateRepository = playbackStateRepository,
            filesDir = lightContext.filesDir,
        )

        // Periodic backstop (WorkManager's own 15-minute floor — see
        // scheduleSyncQueueJob) for when the app isn't in the foreground to
        // observe a reconnect below. Registering this is idempotent
        // (ExistingPeriodicWorkPolicy.UPDATE) so it's safe to call on every
        // process start, not just the first ever launch.
        scheduleSyncQueueJob(lightContext)

        // The near-instant path for the common case: the app is already open
        // when connectivity comes back, so there's no reason to wait out the
        // periodic floor above. `observeNetworkStatus()` emits once
        // immediately with the current status too, which is deliberately not
        // filtered out here — draining on a fresh app launch that's already
        // online, in case anything was queued from a previous offline
        // session, is exactly the behavior wanted, not just future
        // transitions.
        //
        // Distinct `tag` from the periodic schedule above, even though both
        // point at the same job — `LightWork.enqueue`'s one-time request uses
        // `ExistingWorkPolicy.REPLACE` on whatever uniqueness slot it's given,
        // and the periodic schedule above lives under the plain job-key slot.
        // Sharing that slot would mean every single reconnect replaces (i.e.
        // destroys) the periodic backstop with a one-time job, so it'd only
        // ever come back at the next app launch instead of surviving in the
        // background the way a periodic schedule is supposed to.
        appScope.launch {
            connectivity.observeNetworkStatus()
                .map { it.isConnected }
                .distinctUntilChanged()
                .collect { isConnected ->
                    if (isConnected) {
                        LightWork.enqueue(lightContext, SyncQueueRepository.JOB_KEY, tag = "${SyncQueueRepository.JOB_KEY}-reconnect")
                    }
                }
        }

        return Graph(
            serverConfigRepository = serverConfigRepository,
            appSettingsRepository = appSettingsRepository,
            playbackStateRepository = playbackStateRepository,
            apiHolder = apiHolder,
            database = database,
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
            downloadRepository = downloadRepository,
            albumArtRepository = albumArtRepository,
            lyricsRepository = lyricsRepository,
            syncQueueRepository = syncQueueRepository,
            localDataRepository = localDataRepository,
            connectivity = connectivity,
        )
    }
}
