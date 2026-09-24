package com.musicplus.app.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.musicplus.app.data.playback.StreamCache
import java.io.File
import java.io.IOException

/**
 * One-time carry-over of everything an install already holds to server-scoped ids (see [ServerScope]):
 * every id in the database, the paths of downloaded songs, the downloaded files themselves, cached
 * cover art and lyrics, the saved queue and any edits still waiting to sync.
 *
 * Nothing here changes a table's shape, only the ids inside it, so Room's schema hash is untouched.
 * The rows and files all belong to one server, whichever was active when this ran: an install could
 * never hold two servers' data apart before this, so that is the only owner they can have.
 *
 * **All or nothing.** The database rewrite, the file renames and the version bump happen inside one
 * transaction. A rename that already happened is undone if anything after it fails, and every rename
 * is safe to repeat, so a process killed half way simply carries on the next launch. Renames are also
 * written to [JOURNAL_NAME] so they can be reversed by hand, and a copy of the database as it was is
 * kept beside it as `<db>.pre-scope`.
 */
internal object IdScopeMigration {
    /** Owner for rows found when no server is configured at all — visible under no server, so never shown. */
    const val UNASSIGNED_SERVER = "unassigned"
    const val JOURNAL_NAME = "id-scope-migration.tsv"

    private const val TAG = "IdScopeMigration"

    fun run(db: SQLiteDatabase, serverId: String, filesDir: File, newVersion: Int) {
        val prefix = ServerScope.scope(serverId, "")
        val renames = ArrayList<Pair<File, File>>()
        AppLogger.d(TAG, "scoping existing data to server $serverId")
        keepCopyOf(db)
        try {
            db.beginTransaction()
            try {
                val before = rowCounts(db)
                val coverArtIds = strings(
                    db,
                    "SELECT coverArtId FROM artists WHERE coverArtId IS NOT NULL UNION " +
                        "SELECT coverArtId FROM albums WHERE coverArtId IS NOT NULL UNION " +
                        "SELECT coverArtId FROM tracks WHERE coverArtId IS NOT NULL",
                )
                val trackIds = strings(db, "SELECT id FROM tracks")

                // Paths and files first: they are keyed by the ids as they are now.
                scopeDownloadedFiles(db, prefix, File(filesDir, "downloads"), renames)

                scopeColumns(db, prefix, "artists", "id", "coverArtId")
                scopeColumns(db, prefix, "albums", "id", "artistId", "coverArtId")
                scopeColumns(db, prefix, "tracks", "id", "albumId", "artistId", "coverArtId")
                scopeColumns(db, prefix, "playlists", "id")
                scopeColumns(db, prefix, "playlist_tracks", "playlistId", "songId")
                scopeColumns(db, prefix, "downloads", "songId")
                scopeColumns(db, prefix, "queue_items", "songId")
                scopeColumns(db, prefix, "pending_mutations", "targetId")
                scopeMutationPayloads(db, prefix)

                // A download that was still in flight belongs to a background job that knows the old id and
                // will find nothing; marking it failed hands it to the Wi-Fi retry that re-queues under the new one.
                db.execSQL("UPDATE downloads SET status = 'FAILED' WHERE status IN ('QUEUED', 'DOWNLOADING')")

                verify(db, prefix, before)

                renameCached(File(filesDir, "albumart"), ART_FILE, coverArtIds, prefix, renames)
                renameCached(File(filesDir, "lyrics"), LYRICS_FILE, trackIds, prefix, renames)

                db.version = newVersion
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            undo(renames)
            throw e
        }
        writeJournal(filesDir, renames)
        clearStreamCache(filesDir)
        AppLogger.d(TAG, "done: ${renames.size} files renamed")
    }

    // ---- database ----

    private fun scopeColumns(db: SQLiteDatabase, prefix: String, table: String, vararg columns: String) {
        val sets = columns.joinToString(", ") { "$it = CASE WHEN $it IS NULL THEN NULL ELSE ? || $it END" }
        db.execSQL("UPDATE $table SET $sets", Array(columns.size) { prefix })
    }

    /** `songId`/`songIds` inside a queued edit's JSON (a favorite or rename carries none). Undecodable rows are left alone: the sync queue drops those itself. */
    private fun scopeMutationPayloads(db: SQLiteDatabase, prefix: String) {
        db.rawQuery("SELECT id, payloadJson FROM pending_mutations", null).use { cursor ->
            val rewrites = ArrayList<Pair<Long, String>>()
            while (cursor.moveToNext()) {
                val rewritten = runCatching { scopePayload(cursor.getString(1), prefix) }.getOrNull() ?: continue
                rewrites += cursor.getLong(0) to rewritten
            }
            for ((rowId, json) in rewrites) {
                db.execSQL("UPDATE pending_mutations SET payloadJson = ? WHERE id = ?", arrayOf<Any>(json, rowId))
            }
        }
    }

    private fun scopePayload(json: String, prefix: String): String {
        fun scoped(value: kotlinx.serialization.json.JsonElement) =
            if (value is JsonPrimitive && value.isString) JsonPrimitive(prefix + value.content) else value
        val payload = Json.parseToJsonElement(json) as JsonObject
        val out = payload.mapValues { (key, value) ->
            when {
                key == "songId" -> scoped(value)
                key == "songIds" && value is JsonArray -> JsonArray(value.map { scoped(it) })
                else -> value
            }
        }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private val COUNTED_TABLES = listOf(
        "artists", "albums", "tracks", "playlists", "playlist_tracks", "downloads", "queue_items", "pending_mutations",
    )

    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> =
        COUNTED_TABLES.associateWith { db.rawQuery("SELECT COUNT(*) FROM $it", null).use { c -> c.moveToFirst(); c.getLong(0) } }

    /** Same rows as before, and every id column now starts with the prefix. Throws (rolling everything back) if not. */
    private fun verify(db: SQLiteDatabase, prefix: String, before: Map<String, Long>) {
        val after = rowCounts(db)
        check(after == before) { "row counts changed: $before -> $after" }
        val columns = listOf(
            "artists" to "id", "albums" to "id", "albums" to "artistId", "tracks" to "id", "tracks" to "albumId",
            "tracks" to "artistId", "tracks" to "coverArtId", "playlists" to "id", "playlist_tracks" to "playlistId",
            "playlist_tracks" to "songId", "downloads" to "songId", "queue_items" to "songId", "pending_mutations" to "targetId",
        )
        for ((table, column) in columns) {
            val unscoped = db.rawQuery(
                "SELECT COUNT(*) FROM $table WHERE $column IS NOT NULL AND substr($column, 1, ${prefix.length}) <> ?",
                arrayOf(prefix),
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            check(unscoped == 0L) { "$unscoped rows in $table.$column are not scoped" }
        }
    }

    private fun strings(db: SQLiteDatabase, sql: String): List<String> =
        db.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** A consistent copy of the database as it was, for a fix by hand if this ever commits something wrong. Kept once. */
    private fun keepCopyOf(db: SQLiteDatabase) {
        val copy = File(db.path + ".pre-scope")
        if (copy.exists()) return
        runCatching { db.execSQL("VACUUM INTO '${copy.path.replace("'", "''")}'") }
            .onFailure { AppLogger.e(TAG, "could not keep a copy of the database first", it) }
    }

    // ---- files ----

    /** Downloaded songs: `downloads/<id>.<ext>` becomes `downloads/<scoped id>.<ext>`, and the stored path follows. */
    private fun scopeDownloadedFiles(db: SQLiteDatabase, prefix: String, downloadsDir: File, renames: MutableList<Pair<File, File>>) {
        val rows = db.rawQuery("SELECT songId, localFilePath FROM downloads WHERE localFilePath IS NOT NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        for ((songId, path) in rows) {
            val old = File(path)
            val target = File(downloadsDir, "${ServerScope.fileKey(prefix + songId)}.${old.extension.ifEmpty { "mp3" }}")
            if (old.path == target.path) continue
            // Not there and not there-already means the file was already gone; the row keeps pointing at where it belongs.
            if (old.exists() && !target.exists()) rename(old, target, renames)
            db.execSQL("UPDATE downloads SET localFilePath = ? WHERE songId = ?", arrayOf<Any>(target.path, songId))
        }
    }

    private val ART_FILE = Regex("^(.+)-(\\d+)\\.art$")
    private val LYRICS_FILE = Regex("^(.+)\\.json$")

    /** Cached art (`<key>-<size>.art`) and lyrics (`<key>.json`): keyed by the sanitized id, so only files whose id is still in the library are carried over. */
    private fun renameCached(dir: File, pattern: Regex, oldIds: Collection<String>, prefix: String, renames: MutableList<Pair<File, File>>) {
        val files = dir.listFiles() ?: return
        val newKeys = oldIds.associate { ServerScope.fileKey(it) to ServerScope.fileKey(prefix + it) }
        for (file in files) {
            val match = pattern.matchEntire(file.name) ?: continue
            val newKey = newKeys[match.groupValues[1]] ?: continue
            val rest = file.name.removePrefix(match.groupValues[1])
            val target = File(dir, newKey + rest)
            if (!target.exists()) rename(file, target, renames)
        }
    }

    private fun rename(from: File, to: File, renames: MutableList<Pair<File, File>>) {
        if (!from.renameTo(to)) throw IOException("could not rename ${from.name}")
        renames += from to to
    }

    private fun undo(renames: List<Pair<File, File>>) {
        for ((from, to) in renames.asReversed()) {
            if (to.exists() && !from.exists()) to.renameTo(from)
        }
    }

    private fun writeJournal(filesDir: File, renames: List<Pair<File, File>>) {
        if (renames.isEmpty()) return
        runCatching {
            File(filesDir, JOURNAL_NAME).appendText(renames.joinToString(separator = "\n", postfix = "\n") { (from, to) -> "${from.path}\t${to.path}" })
        }
    }

    /** Streams cached under the old names would never be found again; it is only a cache. */
    private fun clearStreamCache(filesDir: File) {
        StreamCache(File(filesDir, "streamcache")).clear()
    }
}
