package com.musicplus.app.data

/**
 * Something the phone keeps files for, that can forget them: a server's (when it is removed) or all of them (when the person clears the
 * local data). Each kind of kept file owns its own directory and naming (the stream cache, cover art, lyrics, downloads), so what has to
 * happen to them is asked of them, not worked out by whoever is wiping: adding a new kind of file is one new implementation in the list
 * [PhoneLeftovers] and [LocalDataRepository] are given.
 */
interface KeptFiles {
    /** Deletes what is kept for songs, albums and artists of [serverId]. Files are named after the scoped id, so a server's are the ones starting with its file-safe scope. */
    fun deleteForServer(serverId: String)

    /** Deletes everything kept. */
    fun clear()
}

/** The prefix a server's kept files start with: its scope, made safe for a file name. */
internal fun serverFilePrefix(serverId: String): String = ServerScope.fileKey(ServerScope.scope(serverId, ""))
