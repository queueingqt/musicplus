package com.musicplus.app.data

import com.musicplus.app.BuildConfig
import com.musicplus.app.data.playback.StreamCache
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Composition root. A single process-lifetime instance, built synchronously the
 * first time any screen asks for it — `LightScreen.createViewModel()` isn't a
 * suspend function, so nothing here can await an async read (see
 * [ApiHolder] for how the server-dependent pieces defer past that).
 */
object AppGraph {
    class Graph(
        val serverConfigRepository: ServerConfigRepository,
        val appSettingsRepository: AppSettingsRepository,
        val playbackStateRepository: PlaybackStateRepository,
        val apiHolder: ApiHolder,
        val database: MusicPlusDatabase,
        val libraryRepository: LibraryRepository,
        val playlistRepository: PlaylistRepository,
        val listRefresher: ListRefresher,
        val downloadRepository: DownloadRepository,
        val albumArtRepository: AlbumArtRepository,
        val lyricsRepository: LyricsRepository,
        val syncQueueRepository: SyncQueueRepository,
        val localDataRepository: LocalDataRepository,
        val serverLifecycle: ServerLifecycle,
        val serverLogin: ServerLogin,
        val connectivity: LightConnectivity,
        val streamCache: StreamCache,
        val fetchGate: FetchGate,
        /** What screens read of the warm state, handed to them rather than reached for: see [AppState]. */
        val appState: AppState,
    )

    @Volatile private var instance: Graph? = null

    /** Bump when the capability probes change, so every server is asked again on the next launch even within one app version. */
    private const val CAPABILITY_PROBE_REVISION = 1

    // Process-lifetime, not tied to any screen's own viewModelScope — needed so
    // the settings mirrors keep being observed no matter which screen is
    // currently on top, including screens that never touch
    // AppSettingsRepository themselves.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun from(lightContext: SealedLightContext): Graph =
        instance ?: synchronized(this) {
            instance ?: build(lightContext).also { instance = it }
        }

    // Every AppSettingsRepository flow that just needs to keep a process-lifetime
    // WarmedFlow in sync follows
    // this exact shape — collapsed to one helper so build() reads as "mirror these
    // settings" plus the two genuinely special bootstrap steps, not a pile of
    // same-shaped appScope.launch blocks.
    private fun <T> mirrorInto(flow: Flow<T>, sink: (T) -> Unit) {
        appScope.launch { flow.retryOnTransientDbError().collect(sink) }
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
        appScope.launch {
            serverConfigRepository.migrateLegacyConfigIfNeeded()
        }
        val appSettingsRepository = AppSettingsRepository(lightContext.dataStore)
        val playbackStateRepository = PlaybackStateRepository(lightContext.dataStore)
        Settings.mirrorAll(lightContext.dataStore, appScope)
        mirrorInto(serverConfigRepository.servers, AppServerPrefs.servers::set)
        mirrorInto(serverConfigRepository.activeServerId, AppServerPrefs.activeServerId::set)
        mirrorInto(serverConfigRepository.enabledServerIds, AppServerPrefs.enabledServerIds::set)
        mirrorInto(serverConfigRepository.removedServers, AppServerPrefs.removedServers::set)
        mirrorInto(serverConfigRepository.servers.map { it.isNotEmpty() }, AppServerPrefs.isConfigured::set)
        val serverSyncStatus = ServerSyncStatus(lightContext.dataStore)
        mirrorInto(serverSyncStatus.lastSynced, AppServerPrefs.lastSyncedAt::set)
        val apiHolder = ApiHolder(serverConfigRepository)
        // What runs for one server (a refresh, a capability probe) runs under it, so removing the server can stop it: see ServerWork.
        val serverWork = ServerWork()
        val reachability = ServerReachability(appScope, apiHolder)
        apiHolder.reachability = reachability
        // Existing rows and files predate server-scoped ids. They belong to whichever server is active now,
        // which lives in DataStore, so the (one-time) migration asks for it only if it has something to migrate.
        val database = MusicPlusDatabase.create(lightContext) {
            runBlocking { serverConfigRepository.activeProfile.first()?.id }
        }
        // `SealedLightContext.androidContext` is internal to :sdk:client (not visible
        // to a consumer module like this one) — it already exposes a `connectivity`
        // property built from it for exactly this reason.
        val connectivity = lightContext.connectivity
        // What each server can do (favorites, scrobbling, lyrics, playlists), found out with harmless requests when a
        // server is added or turned on and after every app update, and corrected by real use. See CapabilityRegistry.
        val capabilityRegistry = CapabilityRegistry(lightContext.dataStore, appScope, apiHolder, connectivity, build = "${BuildConfig.VERSION_NAME}#$CAPABILITY_PROBE_REVISION", work = serverWork)
        apiHolder.learner = capabilityRegistry
        mirrorInto(capabilityRegistry.all, AppServerPrefs.capabilities::set)
        // Chooses how many transfers run at once (few on cellular, many on Wi-Fi) and in what order, and is where "are we on Wi-Fi" is answered.
        val fetchGate = FetchGate(isWifiNow = { connectivity.currentStatus.isWifi })
        val streamCache = StreamCache(File(lightContext.filesDir, StreamCache.DIR_NAME), fetchGate)
        TrackAvailability.init(streamCache)
        // Built before libraryRepository/playlistRepository — both now take
        // this as a dependency (see LibraryRepository's own class doc for
        // why) to join live download status into every Track they hand out.
        val downloadRepository = DownloadRepository(
            downloadDao = database.downloadDao(),
            scheduler = LightWorkDownloadScheduler(lightContext),
            sources = AppDownloadSources(database.trackDao(), apiHolder, appSettingsRepository),
            gate = FetchGateDownloadGate(fetchGate),
            downloadsDir = File(lightContext.filesDir, DownloadRepository.DIRECTORY),
        )
        // One refresh runner for the library and the playlists, so a server that is removed is stopped in both at once.
        val serverRefresh = ServerRefresh(
            isConnected = { connectivity.currentStatus.isConnected },
            apis = apiHolder,
            shownServerIds = serverConfigRepository.shownServerIds,
            onRefreshed = { serverSyncStatus.refreshed(it) },
            work = serverWork,
        )
        val libraryRepository = LibraryRepository(
            apiHolder = apiHolder,
            artistDao = database.artistDao(),
            albumDao = database.albumDao(),
            trackDao = database.trackDao(),
            pendingMutationDao = database.pendingMutationDao(),
            connectivity = connectivity,
            downloadRepository = downloadRepository,
            shownServerIds = serverConfigRepository.shownServerIds,
            serverRefresh = serverRefresh,
        )
        // The phone's own copy of the library, as far as a write is concerned (see LocalFirstWrites).
        val localCopy = RoomLocalCopy(database.artistDao(), database.albumDao(), database.trackDao(), database.playlistDao())
        val playlistRepository = PlaylistRepository(
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            downloadRepository = downloadRepository,
            shownServerIds = serverConfigRepository.shownServerIds,
            serverRefresh = serverRefresh,
            localCopy = localCopy,
            localStars = { database.pendingMutationDao().localStars() },
        )
        // Starts every list refresh — on app start / reconnect just below, and
        // whenever a list page is opened (each list screen's onScreenShow). See
        // ListRefresher's own doc.
        val listRefresher = ListRefresher(appScope, libraryRepository, playlistRepository)
        // See AppLibraryCache's own doc — every list screen previously paid
        // a fresh Room-query-plus-mapping cost on every single visit (a
        // fresh ViewModel every time, confirmed live 2026-09-18 as multiple
        // real seconds for Songs, a smaller but still real and reported-live
        // cost for Albums/Artists too). Mirrored here instead, starting the
        // moment the composition root builds rather than waiting for first
        // navigation to that specific screen.
        mirrorInto(libraryRepository.observeArtists(), AppLibraryCache.artists::set)
        mirrorInto(libraryRepository.observeAlbums(), AppLibraryCache.albums::set)
        mirrorInto(libraryRepository.observeAllTracks(), AppLibraryCache.allTracks::set)
        mirrorInto(libraryRepository.observeFavoriteArtists(), AppLibraryCache.favoriteArtists::set)
        mirrorInto(libraryRepository.observeFavoriteAlbums(), AppLibraryCache.favoriteAlbums::set)
        mirrorInto(libraryRepository.observeFavoriteTracks(), AppLibraryCache.favoriteTracks::set)
        mirrorInto(playlistRepository.observePlaylists(), AppLibraryCache.playlists::set)
        // Which rows can be played right now, for every list: see ListAvailability. Built from the caches just above, the stream cache's
        // listing, and each playlist's songs.
        mirrorInto(
            observeOnPhoneIndex(AppLibraryCache.allTracks.value, AppLibraryCache.albums.value, streamCache.copyKeys, database.playlistDao().observeMemberships()),
            AppAvailability.index::set,
        )
        mirrorInto(AppAvailability.observe(), AppAvailability.now::set)
        // And each list's rows already split by it, so opening a list reads a finished answer (see AppAvailability).
        mirrorInto(AppAvailability.split(AppLibraryCache.allTracks.value, AppLibraryCache.allTracks.loaded, ListAvailability::songs), AppAvailability.songs::set)
        mirrorInto(AppAvailability.split(AppLibraryCache.albums.value, AppLibraryCache.albums.loaded, ListAvailability::albums), AppAvailability.albums::set)
        mirrorInto(AppAvailability.split(AppLibraryCache.artists.value, AppLibraryCache.artists.loaded, ListAvailability::artists), AppAvailability.artists::set)
        mirrorInto(AppAvailability.split(AppLibraryCache.playlists.value, AppLibraryCache.playlists.loaded, ListAvailability::playlists), AppAvailability.playlists::set)
        mirrorInto(AppAvailability.split(AppLibraryCache.favoriteTracks.value, AppLibraryCache.favoriteTracks.loaded, ListAvailability::songs), AppAvailability.favoriteTracks::set)
        mirrorInto(AppAvailability.split(AppLibraryCache.favoriteAlbums.value, AppLibraryCache.favoriteAlbums.loaded, ListAvailability::albums), AppAvailability.favoriteAlbums::set)
        mirrorInto(AppAvailability.split(AppLibraryCache.favoriteArtists.value, AppLibraryCache.favoriteArtists.loaded, ListAvailability::artists), AppAvailability.favoriteArtists::set)
        val albumArtRepository = AlbumArtRepository(
            apiHolder = apiHolder,
            filesDir = lightContext.filesDir,
        )
        val lyricsStore = LyricsStore(File(lightContext.filesDir, LyricsStore.DIR_NAME))
        val lyricsRepository = LyricsRepository(
            apiHolder = apiHolder,
            store = lyricsStore,
        )
        val localFirstWrites = LocalFirstWrites(
            queue = database.pendingMutationDao(),
            local = localCopy,
            apis = apiHolder,
            enabledServerIds = serverConfigRepository.enabledServerIds,
            keepsFavorites = { ServerStandingNow.current().keepsFavorites(it) },
            reconcile = { playlistRepository.refreshPlaylistDetail(it) },
        )
        val syncQueueRepository = SyncQueueRepository(
            writes = localFirstWrites,
            favorites = Favorites(localFirstWrites) { id, favorite -> PlaybackRepositoryHolder.peek()?.updateTrackFavorite(id, favorite) },
            local = localCopy,
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
            apis = apiHolder,
        )
        val localDataRepository = LocalDataRepository(
            database = database,
            playbackStateRepository = playbackStateRepository,
            files = listOf(streamCache, albumArtRepository, lyricsStore, downloadRepository),
        )

        // Periodic backstop (WorkManager's own 15-minute floor — see
        // scheduleSyncQueueJob) for when the app isn't in the foreground to
        // observe a reconnect below. Registering this is idempotent
        // (ExistingPeriodicWorkPolicy.UPDATE) so it's safe to call on every
        // process start, not just the first ever launch.
        scheduleSyncQueueJob(lightContext)

        // The near-instant path for the common case (the app is open when connectivity comes back) is AppLifecycle's: on every change to a
        // connected network it asks this to run. Distinct `tag` from the periodic schedule above, even though both point at the same job:
        // `LightWork.enqueue`'s one-time request uses `ExistingWorkPolicy.REPLACE` on whatever uniqueness slot it is given, and the periodic
        // schedule lives under the plain job-key slot. Sharing it would mean every reconnect replaces (destroys) the periodic backstop with a
        // one-time job. Each list refreshes on its own coroutine, so none makes the others (or the next reconnect) wait; list pages also
        // refresh when opened and ListRefresher skips a list already being refreshed, so the two do not double up.
        val lifecycle = AppLifecycle(
            scope = appScope,
            networkStatuses = connectivity.observeNetworkStatus(),
            isWifi = fetchGate::isWifi,
            onReconnected = {
                reachability.recheckNow()
                LightWork.enqueue(lightContext, SyncQueueRepository.JOB_KEY, tag = "${SyncQueueRepository.JOB_KEY}-reconnect")
                listRefresher.refreshAll()
            },
            retryFailedDownloads = downloadRepository::retryFailed,
            mediaRepair = AppLifecycle.MediaRepairPolicy(
                checkedVersion = { appSettingsRepository.mediaIntegrityCheckedVersion.first() },
                currentVersion = MediaIntegrity.VERSION,
                anyServerSetUp = { apiHolder.get() != null },
                repair = { requeue -> MediaIntegrity(database.downloadDao(), database.trackDao(), streamCache, downloadRepository).repair(apiHolder, requeue) },
                recordChecked = appSettingsRepository::setMediaIntegrityCheckedVersion,
            ),
        )
        lifecycle.start()

        // A server that gains favorites gets the hearts that were kept on the phone in the meantime.
        capabilityRegistry.onStarGained = { serverId ->
            appScope.launch {
                syncQueueRepository.sendPhoneOnlyFavorites(serverId)
                LightWork.enqueue(lightContext, SyncQueueRepository.JOB_KEY, tag = "${SyncQueueRepository.JOB_KEY}-favorites")
            }
        }
        capabilityRegistry.watch(serverConfigRepository.enabledServers)

        val serverLogin = ServerLogin(
            jellyfinDeviceId = appSettingsRepository::jellyfinDeviceId,
            appVersion = BuildConfig.VERSION_NAME,
        )
        val serverLifecycle = ServerLifecycle(
            scope = appScope,
            registry = serverConfigRepository,
            login = serverLogin,
            work = serverWork,
            // Everything that holds state per server. A new holder is added here once, and forgetting a server reaches it.
            holders = listOf(apiHolder, serverSyncStatus, capabilityRegistry, reachability),
            leftovers = PhoneLeftovers(
                playbackStateRepository = playbackStateRepository,
                downloadRepository = downloadRepository,
                serverCleanupDao = database.serverCleanupDao(),
                downloadDao = database.downloadDao(),
                pendingMutationDao = database.pendingMutationDao(),
                queueDao = database.queueDao(),
                streamCopies = streamCache,
                withTheServer = listOf(albumArtRepository, lyricsStore),
            ),
            // Set ahead of the DataStore mirror, because the list refresh that follows reads these and would otherwise still see the servers
            // as they were a moment ago (the lists themselves already follow the servers that are on; this brings a newly shown one up to date).
            announce = { saved ->
                AppServerPrefs.servers.set(saved.servers)
                AppServerPrefs.activeServerId.set(saved.activeId)
                AppServerPrefs.enabledServerIds.set(saved.enabledIds)
                listRefresher.refreshAll()
            },
        )

        return Graph(
            serverConfigRepository = serverConfigRepository,
            appSettingsRepository = appSettingsRepository,
            playbackStateRepository = playbackStateRepository,
            apiHolder = apiHolder,
            database = database,
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
            listRefresher = listRefresher,
            downloadRepository = downloadRepository,
            albumArtRepository = albumArtRepository,
            lyricsRepository = lyricsRepository,
            syncQueueRepository = syncQueueRepository,
            localDataRepository = localDataRepository,
            serverLifecycle = serverLifecycle,
            serverLogin = serverLogin,
            connectivity = connectivity,
            streamCache = streamCache,
            fetchGate = fetchGate,
            appState = AppState.Default,
        )
    }
}
