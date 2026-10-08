package com.musicplus.app.data

/**
 * How one adapter turns the ids its server gave into the app's scoped ids and back (see [ServerScope]). It was copied into each
 * adapter, identical, so a third backend would have had to copy it again; nothing in the app sees a server's own id but the code that
 * calls this.
 */
class ScopedIds(private val serverId: String, private val backend: String) {
    /** A server's own [id] as the app knows it: `<serverId>:<id>`. */
    fun scope(id: String): String = ServerScope.scope(serverId, id)

    /**
     * The id as this server knows it. An id that was never scoped is passed through and logged: it means a code path missed the
     * scoping. An id that belongs to another server is a bug that would have asked this server about someone else's item, so it fails.
     */
    fun native(id: String): String {
        val owner = ServerScope.serverOf(id)
        if (owner == null) {
            AppLogger.e(backend, "unscoped id \"$id\" sent to server $serverId")
            return id
        }
        require(owner == serverId) { "id $id belongs to server $owner, not $serverId" }
        return ServerScope.nativeOf(id)
    }
}
