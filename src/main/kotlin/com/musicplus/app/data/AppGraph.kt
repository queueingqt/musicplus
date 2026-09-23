package com.musicplus.app.data

import com.musicplus.app.BuildConfig
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

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
        val serverRemoval: ServerRemoval,
        val connectivity: LightConnectivity,
    )

    @Volatile private var instance: Graph? = null

    /** Bump when the capability probes change, so every server is asked again on the next launch even within one app version. */
    private const val CAPABILITY_PROBE_REVISION = 1

    private val VERSION_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

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

    /**
     * Call after a server was switched on or off, added or removed. The lists already follow the servers that are on
     * (they are limited to those servers' rows), so this only has to bring a newly shown server's rows up to date. It
     * sets the warm values itself, ahead of the DataStore mirror, because the refresh below reads them and would
     * otherwise still see the servers as they were a moment ago.
     */
    fun serversChanged(activeServerId: String?, enabledServerIds: Set<String>) {
        AppServerPrefs.activeServerId.set(activeServerId)
        AppServerPrefs.enabledServerIds.set(enabledServerIds)
        instance?.listRefresher?.refreshAll()
    }

    // Every AppSettingsRepository flow that just needs to keep a process-lifetime
    // WarmedFlow (AppLogger, AppDisplayPrefs, AppQualityPrefs, ...) in sync follows
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
        // Chains its own uncaught-exception handler after AppLogger's (see
        // CrashReporter.init) — both run on a crash, not one-or-the-other.
        CrashReporter.init(lightContext.filesDir)

        val serverConfigRepository = ServerConfigRepository(lightContext.dataStore)
        appScope.launch {
            serverConfigRepository.migrateLegacyConfigIfNeeded()
        }
        val appSettingsRepository = AppSettingsRepository(lightContext.dataStore)
        val playbackStateRepository = PlaybackStateRepository(lightContext.dataStore)
        mirrorInto(appSettingsRepository.debugLoggingEnabled) {
            AppLogger.setEnabled(it)
            CrashReporter.setEnabled(it)
            AppDebugPrefs.debugLoggingEnabled.set(it)
        }
        mirrorInto(appSettingsRepository.showAlbumArtwork, AppDisplayPrefs.showAlbumArtwork::set)
        mirrorInto(appSettingsRepository.streamQualityWifi, AppQualityPrefs.streamQualityWifi::set)
        mirrorInto(appSettingsRepository.streamQualityCellular, AppQualityPrefs.streamQualityCellular::set)
        mirrorInto(appSettingsRepository.downloadQuality, AppQualityPrefs.downloadQuality::set)
        mirrorInto(serverConfigRepository.servers, AppServerPrefs.servers::set)
        mirrorInto(serverConfigRepository.activeServerId, AppServerPrefs.activeServerId::set)
        mirrorInto(serverConfigRepository.enabledServerIds, AppServerPrefs.enabledServerIds::set)
        mirrorInto(serverConfigRepository.removedServers, AppServerPrefs.removedServers::set)
        mirrorInto(serverConfigRepository.servers.map { it.isNotEmpty() }, AppServerPrefs.isConfigured::set)
        val serverSyncStatus = ServerSyncStatus(lightContext.dataStore)
        mirrorInto(serverSyncStatus.lastSynced, AppServerPrefs.lastSyncedAt::set)
        mirrorInto(appSettingsRepository.scrobblingEnabled, AppScrobblePrefs.scrobblingEnabled::set)
        val apiHolder = ApiHolder(serverConfigRepository)
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
        val capabilityRegistry = CapabilityRegistry(lightContext.dataStore, appScope, apiHolder, connectivity, build = "${BuildConfig.VERSION_NAME}#$CAPABILITY_PROBE_REVISION")
        apiHolder.learner = capabilityRegistry
        mirrorInto(capabilityRegistry.all, AppServerPrefs.capabilities::set)
        TrackAvailability.init(lightContext.filesDir)
        // Lets FetchGate choose how many transfers to run at once: few on cellular, many on Wi-Fi.
        FetchGate.attach(connectivity)
        // Built before libraryRepository/playlistRepository — both now take
        // this as a dependency (see LibraryRepository's own class doc for
        // why) to join live download status into every Track they hand out.
        val downloadRepository = DownloadRepository(
            downloadDao = database.downloadDao(),
            trackDao = database.trackDao(),
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
            serverSyncStatus = serverSyncStatus,
        )
        val playlistRepository = PlaylistRepository(
            apiHolder = apiHolder,
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            connectivity = connectivity,
            downloadRepository = downloadRepository,
            shownServerIds = serverConfigRepository.shownServerIds,
            serverSyncStatus = serverSyncStatus,
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
        // Checked on every app open, but throttled to once per
        // VERSION_CHECK_INTERVAL_MS — an unauthenticated GitHub API call is
        // cheap, but someone opening/closing the app dozens of times a day
        // shouldn't turn into dozens of requests against the same rate limit
        // every other GitHub call in this app shares.
        appScope.launch {
            val lastCheckedAt = appSettingsRepository.lastVersionCheckAtMs.first()
            val now = System.currentTimeMillis()
            if (lastCheckedAt == null || now - lastCheckedAt > VERSION_CHECK_INTERVAL_MS) {
                VersionCheckRepository.checkForUpdate(BuildConfig.VERSION_NAME)
                appSettingsRepository.setLastVersionCheckAtMs(now)
            }
        }
        // One-shot per process start, same "give it a fresh shot on reopen" as the
        // sync queue's reconnect-observer below (which also fires once immediately
        // on every launch) — a download that gave up after MAX_DOWNLOAD_ATTEMPTS
        // shouldn't need the user to notice and manually retry it if whatever
        // broke (server down, network blip) has since cleared on its own.
        // Only on Wi-Fi: with the request cap gone (see newJsonHttpClient) these
        // retries now actually finish, and on a phone that is often on cellular a
        // backlog of failed albums would otherwise be pulled over the data plan
        // unasked. A tap on "download" still works on any network.
        appScope.launch {
            if (connectivity.currentStatus.isWifi) downloadRepository.retryFailed(lightContext)
        }
        // Once per install (per MediaIntegrity.VERSION): find songs an earlier
        // version stored cut short and fetch them again. Waits for a connection
        // — it has to ask the server for each song's real size — and only counts
        // as done when every answer came back, so an offline launch tries again
        // next time.
        appScope.launch {
            if ((appSettingsRepository.mediaIntegrityCheckedVersion.first() ?: 0) >= MediaIntegrity.VERSION) return@launch
            connectivity.observeNetworkStatus().first { it.isConnected }
            if (apiHolder.get() == null) return@launch
            val integrity = MediaIntegrity(database.downloadDao(), database.trackDao(), lightContext.filesDir)
            val onWifi = connectivity.currentStatus.isWifi
            if (integrity.repair(lightContext, apiHolder, requeue = onWifi)) appSettingsRepository.setMediaIntegrityCheckedVersion(MediaIntegrity.VERSION)
        }
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
            enabledServerIds = serverConfigRepository.enabledServerIds,
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
        // transitions. That "fires immediately if already online" behavior
        // is also why the 4 library refreshes below live here rather than as
        // a separate one-shot block: this one collector already covers both
        // "fresh launch, online" and "was offline at launch, came back
        // later" without doing it twice. (List pages also refresh themselves
        // each time they're opened — ListRefresher skips a list that is
        // already being refreshed, so this and a page opened right after
        // launch don't double up.)
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
                        reachability.recheckNow()
                        LightWork.enqueue(lightContext, SyncQueueRepository.JOB_KEY, tag = "${SyncQueueRepository.JOB_KEY}-reconnect")
                        // Each list on its own coroutine, not awaited in
                        // sequence — refreshAllSongs in particular can be a
                        // multi-page fetch, and none of these should make the
                        // others (or the next reconnect event) wait.
                        listRefresher.refreshAll()
                    }
                }
        }

        // A server that gains favorites gets the hearts that were kept on the phone in the meantime.
        capabilityRegistry.onStarGained = { serverId ->
            appScope.launch {
                syncQueueRepository.sendPhoneOnlyFavorites(serverId)
                LightWork.enqueue(lightContext, SyncQueueRepository.JOB_KEY, tag = "${SyncQueueRepository.JOB_KEY}-favorites")
            }
        }
        capabilityRegistry.watch(serverConfigRepository.enabledServers)

        val serverRemoval = ServerRemoval(
            scope = appScope,
            lightContext = lightContext,
            serverConfigRepository = serverConfigRepository,
            serverSyncStatus = serverSyncStatus,
            capabilityRegistry = capabilityRegistry,
            reachability = reachability,
            apiHolder = apiHolder,
            playbackStateRepository = playbackStateRepository,
            downloadRepository = downloadRepository,
            serverCleanupDao = database.serverCleanupDao(),
            downloadDao = database.downloadDao(),
            pendingMutationDao = database.pendingMutationDao(),
            queueDao = database.queueDao(),
            filesDir = lightContext.filesDir,
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
            serverRemoval = serverRemoval,
            connectivity = connectivity,
        )
    }
}
