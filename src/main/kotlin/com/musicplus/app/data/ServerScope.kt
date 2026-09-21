package com.musicplus.app.data

/**
 * The one place that knows what an id in this app looks like.
 *
 * A server hands out ids like `xE8hToLxKMfMu3Q0ldOtAW`, and two servers can hand out the same one. So every
 * id the app stores or passes around is *scoped*: `<serverId>:<id the server gave>`. [SubsonicApi] is the only
 * code that sees a server's own ids: it scopes what comes in and unscopes what goes out, so nothing else can
 * mix the two up, and the same song on two servers is two rows, two files and two queue entries.
 *
 * A server id is a UUID (or "legacy" for a pre-multi-server install) and never contains ':', so the first ':'
 * always ends it, whatever the rest holds. A playlist made while offline is `<serverId>:pending:<uuid>`.
 */
object ServerScope {
    private const val SEPARATOR = ':'

    /**
     * The owner of whatever lives only on this phone (a Phone Only playlist). It is not a server: nothing is ever sent to it,
     * and it is always shown. A real server id is a UUID (or "legacy"), so this can never collide with one.
     */
    const val PHONE = "phone"

    /** What the app calls things kept only on this phone. */
    const val PHONE_ONLY_LABEL = "Phone Only"

    /** A fresh id for a Phone Only playlist: `phone:<uuid>`. */
    fun newPhoneId(): String = scope(PHONE, java.util.UUID.randomUUID().toString())

    fun isPhone(id: String): Boolean = isScopedTo(id, PHONE)

    /**
     * SQL for "the server this row's `id` belongs to". Room queries filter with `<this> IN (:serverIds)`.
     * A row whose id has no ':' (none should exist) yields '' and matches no server.
     */
    const val SQL_SERVER_OF_ID = "substr(id, 1, instr(id, ':') - 1)"

    fun scope(serverId: String, id: String): String = "$serverId$SEPARATOR$id"

    /** The server an id belongs to, or null for an id that was never scoped. */
    fun serverOf(id: String): String? = id.indexOf(SEPARATOR).takeIf { it > 0 }?.let { id.substring(0, it) }

    /** The id the server itself knows this by. An id that was never scoped is returned as it is. */
    fun nativeOf(id: String): String = id.substring(id.indexOf(SEPARATOR) + 1)

    fun isScopedTo(id: String, serverId: String): Boolean =
        id.length > serverId.length && id.startsWith(serverId) && id[serverId.length] == SEPARATOR

    /**
     * The id as a piece of a file name. Art and lyrics have always used this rule, so their existing files
     * keep matching; the migration and the running code both call it, so they cannot drift apart.
     */
    fun fileKey(id: String): String = id.replace(FILE_KEY_UNSAFE, "_")

    private val FILE_KEY_UNSAFE = Regex("[^A-Za-z0-9_-]")
}
