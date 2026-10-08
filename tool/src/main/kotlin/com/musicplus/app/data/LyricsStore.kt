package com.musicplus.app.data

import java.io.File

/** Where lyrics are kept on disk, one small file per song named after its scoped id. [LyricsRepository] decides what goes in them; this owns the directory and the naming, and what happens when a server is removed. */
class LyricsStore(private val dir: File) : KeptFiles {
    /** The text last written for [trackId], or null if there is none (or it cannot be read). */
    fun read(trackId: String): String? = runCatching { file(trackId).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Best-effort: a disk write failure must not fail the lyrics that were already resolved for this call. */
    fun write(trackId: String, text: String) {
        runCatching {
            dir.mkdirs()
            file(trackId).writeText(text)
        }
    }

    override fun deleteForServer(serverId: String) {
        val prefix = serverFilePrefix(serverId)
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    override fun clear() {
        dir.deleteRecursively()
    }

    private fun file(trackId: String) = File(dir, "${ServerScope.fileKey(trackId)}.json")

    companion object {
        const val DIR_NAME = "lyrics"
    }
}
