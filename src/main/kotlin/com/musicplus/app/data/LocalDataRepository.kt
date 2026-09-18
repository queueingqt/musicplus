package com.musicplus.app.data

import java.io.File

/**
 * Wipes everything the app has cached or downloaded locally — library
 * metadata, favorites cache, downloaded audio, cached album art/lyrics, the
 * play queue, and the offline sync queue — without touching saved server
 * credentials or app preferences. The next launch re-syncs from the server as
 * if freshly installed.
 *
 * Deliberately does NOT touch [ServerConfigRepository] (server URL/
 * credentials) or [AppSettingsRepository] (show album artwork/haptics/debug
 * logging) — this is a cache reset, not a factory reset.
 *
 * Discards any [PendingMutationEntity] rows too — a favorite/playlist edit
 * that hasn't reached the server yet is lost, not just its local cache
 * representation. The caller is responsible for warning about that before
 * calling [clearAll] (see PreferencesScreen, which shows the same live
 * pending-sync count already surfaced there for issue #24).
 */
class LocalDataRepository(
    private val database: MusicPlusDatabase,
    private val playbackStateRepository: PlaybackStateRepository,
    private val filesDir: File,
) {
    suspend fun clearAll() {
        database.artistDao().deleteAll()
        database.albumDao().deleteAll()
        database.trackDao().deleteAll()
        database.playlistDao().deleteAll()
        database.downloadDao().deleteAll()
        database.pendingMutationDao().deleteAll()
        database.queueDao().clear()

        playbackStateRepository.clear()

        for (dirName in listOf("downloads", "streamcache", "lyrics", "albumart")) {
            File(filesDir, dirName).deleteRecursively()
        }

        // A live PlaybackRepository instance (if one exists) still has its own
        // in-memory queue/state from before the wipe — reset that too so the
        // UI doesn't keep showing tracks whose cached files no longer exist.
        PlaybackRepositoryHolder.peek()?.resetInMemoryState()
    }
}
