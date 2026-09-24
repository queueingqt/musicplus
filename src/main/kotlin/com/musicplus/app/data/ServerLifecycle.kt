package com.musicplus.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

/** How many downloaded songs a server has on the phone, and how much space they take. */
data class DownloadSummary(val songs: Int, val bytes: Long)

/** The saved servers as they stand: every profile, which server is the active one (the first that is on), and which are on. */
class SavedServers(val servers: List<ServerProfile>, val activeId: String?, val enabledIds: Set<String>)

/** Where the saved servers live ([ServerConfigRepository] on the phone, a fake in a test). */
interface ServerRegistry {
    suspend fun saved(): SavedServers

    /** Adds a profile (switched on) or replaces the one with the same id; a profile that takes over a [RemovedServer]'s id picks its kept downloads back up. */
    suspend fun addOrUpdate(profile: ServerProfile)

    suspend fun setEnabled(id: String, on: Boolean)

    /** Forgets a server's login; [keptDownloadsAs] is set when its downloaded songs stay on the phone. */
    suspend fun remove(id: String, keptDownloadsAs: RemovedServer? = null)

    /** Drops the record of a removed server (its kept downloads were deleted). */
    suspend fun forgetRemoved(id: String)

    /** The removed server a new login for [baseUrl] and [username] would re-attach to, if any. */
    suspend fun findRemoved(baseUrl: String, username: String): RemovedServer?
}

/** Something that holds state per server (its api, its sync time, its capabilities, whether it is reachable). Registered once with [ServerLifecycle], which is what makes forgetting a server total. */
interface PerServerState {
    /** [serverId] was removed: drop everything held for it. */
    suspend fun forget(serverId: String)

    /** [serverId]'s address or login was edited: drop what was built from the old one. */
    fun edited(serverId: String) {}
}

/** What a server left on the phone besides its login: its cached library, queued edits, upcoming songs, downloads, art, lyrics and streams. */
interface ServerLeftovers {
    /** Finished downloads per server, live. */
    val downloadSummaries: Flow<Map<String, DownloadSummary>>

    suspend fun finishedDownloads(serverId: String): Int

    /**
     * Deletes what a removed server left: its edits still waiting to sync, its upcoming songs in the queue (never the one playing),
     * its downloads that are not finished (and, unless [keepDownloads], the finished ones), its cached library, and its files. Kept
     * downloads stay listed and playable with just the rows they need (each song, its album and artist).
     */
    suspend fun clear(serverId: String, keepDownloads: Boolean)

    /** Deletes the downloads a removed server left kept, with everything else of it. */
    suspend fun clearKept(serverId: String)
}

/** What saving a server form came to. */
sealed interface SaveOutcome {
    object Saved : SaveOutcome

    /** The server did not take the login, or could not be asked, so nothing was saved. */
    data class Refused(val login: LoginResult) : SaveOutcome
}

/**
 * The one owner of a server's life on the phone: adding, editing, switching on and off, and removing it, so no caller has to remember which
 * of six holders of per-server state its change touches, or in which order.
 *
 * Every change ends the same way: the warm copy of the saved servers is brought up to date (ahead of the DataStore mirror, since what
 * follows reads it) and the lists are told to refresh, through [announce]. Removing forgets the login first, so nothing can refresh the
 * server or write its rows back, stops whatever is still running for it ([ServerWork]) so nothing can land after, forgets it in every
 * [PerServerState], and only then deletes what it left on the phone ([ServerLeftovers]). Removing runs on the app's own [scope], never a
 * screen's: closing the Servers screen half way through must not leave half a library behind.
 */
class ServerLifecycle(
    private val scope: CoroutineScope,
    private val registry: ServerRegistry,
    private val login: ServerLogin,
    private val work: ServerWork,
    private val holders: List<PerServerState>,
    private val leftovers: ServerLeftovers,
    /** Told the servers as they now stand, after every change. */
    private val announce: (SavedServers) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val downloadSummaries: Flow<Map<String, DownloadSummary>> get() = leftovers.downloadSummaries

    /** Adds a new server ([existingId] null) or saves an edit of one. A new login for a server that was removed with its downloads kept takes that server's id, so those downloads come back with it instead of sitting beside a second copy. */
    suspend fun save(existingId: String?, draft: ServerDraft): SaveOutcome {
        val baseUrl = draft.baseUrl.trimEnd('/')
        val reattach = if (existingId == null) registry.findRemoved(baseUrl, draft.username) else null
        val id = existingId ?: reattach?.id ?: newId()
        val auth = when (val result = login.forSaving(draft.copy(baseUrl = baseUrl))) {
            is LoginResult.Accepted -> result.auth
            else -> return SaveOutcome.Refused(result)
        }
        registry.addOrUpdate(draft.toProfile(id, auth))
        if (existingId != null) holders.forEach { it.edited(id) }
        announce(registry.saved())
        return SaveOutcome.Saved
    }

    /** Switches a server on or off. Off hides its content everywhere and stops refreshing it; nothing else about it changes. */
    suspend fun setEnabled(serverId: String, on: Boolean) {
        registry.setEnabled(serverId, on)
        announce(registry.saved())
    }

    /** Removes [serverId]. With [keepDownloads] its finished downloads stay on the phone (if it has any). */
    fun remove(serverId: String, keepDownloads: Boolean): Job = scope.launch {
        AppLogger.d(TAG, "removing server $serverId (keep downloads: $keepDownloads)")
        val profile = registry.saved().servers.find { it.id == serverId }
        val finished = leftovers.finishedDownloads(serverId)
        val keep = keepDownloads && finished > 0 && profile != null

        registry.remove(
            serverId,
            keptDownloadsAs = if (keep && profile != null) RemovedServer(serverId, profile.name, profile.baseUrl, profile.username) else null,
        )
        announce(registry.saved())
        work.stop(serverId)
        holders.forEach { it.forget(serverId) }
        leftovers.clear(serverId, keepDownloads = keep)
        AppLogger.d(TAG, "removed server $serverId: kept ${if (keep) finished else 0} downloaded songs")
    }

    /** Deletes the downloads a removed server left, and its record. */
    fun deleteKeptDownloads(serverId: String): Job = scope.launch {
        AppLogger.d(TAG, "deleting the kept downloads of removed server $serverId")
        leftovers.clearKept(serverId)
        registry.forgetRemoved(serverId)
    }

    private companion object {
        const val TAG = "ServerLifecycle"
    }
}
