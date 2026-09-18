package com.musicplus.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import java.io.File

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        DownloadEntity::class,
        QueueItemEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
    ],
    // v2: adds playlists/playlist_tracks (issue #5). See migrateV1ToV2IfNeeded
    // below for how an existing v1 install is carried forward losslessly despite
    // there being no way to register a real Migration object here.
    version = 2,
    exportSchema = false,
)
abstract class MusicPlusDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun queueDao(): QueueDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        // Read verbatim off a real build's generated MusicPlusDatabase_Impl.kt
        // (`build/generated/ksp/debug/kotlin/.../MusicPlusDatabase_Impl.kt`) after
        // adding PlaylistEntity/PlaylistTrackEntity — not invented. This is the
        // `RoomOpenDelegate(2, "<this>", "<legacy>")` hash Room's own onCreate()
        // writes into `room_master_table`, and what its onValidateSchema/identity
        // check expects to find there on every open. It's tied to the CURRENT v2
        // entity shape only — any future schema change invalidates it and needs its
        // own from-scratch hand migration built the same way (rebuild, re-read the
        // freshly generated hash from the new _Impl.kt). There's no way to avoid
        // repeating this by hand each time without an SDK-side fix (see below).
        // Unaffected by the class/file rename from LightwaveDatabase below — Room's
        // identity hash is derived from the entity/column shape, not the class name.
        private const val V2_IDENTITY_HASH = "7ebbf7cb6e6169831fde6abd69471667"

        private const val DB_FILE_NAME = "musicplus.db"
        private const val LEGACY_DB_FILE_NAME = "lightwave.db"

        // `buildDatabase` is the SDK's Room-builder extension on SealedLightContext
        // (sdk/client/.../LightDb.kt) — routes storage through the sandboxed app
        // context LightOS expects rather than a raw Context.
        fun create(lightContext: SealedLightContext): MusicPlusDatabase {
            renameLegacyDbFileIfNeeded(lightContext)
            migrateV1ToV2IfNeeded(lightContext)
            return lightContext.buildDatabase(MusicPlusDatabase::class.java, DB_FILE_NAME)
        }

        /**
         * One-time on-disk rename from this app's old filename ("lightwave.db",
         * left over from when this app was called Lightwave) to "musicplus.db".
         * Room derives its actual on-disk filename from the string passed to
         * buildDatabase(), not from the entity/database class name — so without
         * this step, an already-installed device's local DB (favorites cache,
         * downloads index) would silently orphan: Room would just create a fresh
         * empty "musicplus.db" next to the untouched "lightwave.db", discarding
         * everything already local. Same "patch the file before Room ever opens
         * it" approach as migrateV1ToV2IfNeeded below, for the same reason (no
         * addMigrations hook exposed by this SDK). No-op on a fresh install, or
         * once already renamed.
         */
        private fun renameLegacyDbFileIfNeeded(lightContext: SealedLightContext) {
            try {
                val dir = File(lightContext.filesDir.parentFile, "databases")
                val legacy = File(dir, LEGACY_DB_FILE_NAME)
                val current = File(dir, DB_FILE_NAME)
                if (!legacy.exists() || current.exists()) return
                legacy.renameTo(current)
                for (suffix in listOf("-wal", "-shm", "-journal")) {
                    val legacySidecar = File(dir, "$LEGACY_DB_FILE_NAME$suffix")
                    if (legacySidecar.exists()) legacySidecar.renameTo(File(dir, "$DB_FILE_NAME$suffix"))
                }
            } catch (e: Exception) {
                // Swallow rather than crash from inside our own pre-flight step —
                // worst case Room just creates a fresh musicplus.db, same as a
                // clean install (local cache only; nothing unrecoverable — the
                // server remains the source of truth for everything but the
                // downloads index).
                android.util.Log.e("MusicPlusDatabase", "lightwave.db -> musicplus.db rename failed, falling back to a fresh db", e)
                AppLogger.e("MusicPlusDatabase", "lightwave.db -> musicplus.db rename failed, falling back to a fresh db", e)
            }
        }

        /**
         * Hand-rolled v1 -> v2 migration (adds `playlists`/`playlist_tracks`),
         * applied as a raw-SQLite pre-flight step BEFORE Room ever opens the file —
         * not a real `androidx.room.migration.Migration` object, because there is
         * genuinely no way to register one through this SDK, confirmed by reading
         * both real source files directly (not assumed):
         *
         * - `SealedLightContext.buildDatabase()` (sdk/client/LightDb.kt) is exactly
         *   `Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName).build()`,
         *   with zero configuration exposed to the caller — no `addMigrations(...)`
         *   parameter, no builder handed back, just the already-`.build()`-ed
         *   database. There is no hook here to attach a Migration to.
         * - `SealedLightContext.androidContext` (sdk/client/LightActivity.kt) is
         *   declared `internal val androidContext: Context` — `internal` to
         *   `:sdk:client`, i.e. invisible to this module. So there's also no way to
         *   call `Room.databaseBuilder(...)` directly ourselves instead; the SDK
         *   deliberately never exposes a raw `Context` to tool code (by design —
         *   every other Context-shaped need is wrapped, e.g. `filesDir`,
         *   `dataStore`, `connectivity`).
         *
         * A proper fix is adding an `addMigrations`-accepting overload to
         * `buildDatabase()` in the light-sdk repo itself, which is out of reach from
         * this worktree (a separate repository, not something this change can ship).
         *
         * So instead: open the same SQLite file Room is about to open — its path
         * derived from `filesDir` (the one Context-derived path this module DOES
         * publicly have) via the standard, stable Android convention that
         * `Context.getDatabasePath(name) == <filesDir's parent (the app's data
         * dir)>/databases/<name>` — and, if it already exists with `user_version`
         * still 1, `CREATE TABLE` the two new tables with byte-identical DDL to
         * Room's own generated `createAllTables()` (copied from a real build's
         * generated `MusicPlusDatabase_Impl.kt`, not hand-retyped from the
         * `@Entity` classes — column order/nullability there is exactly what Room's
         * `onValidateSchema` checks against on every open), write the matching
         * `room_master_table` identity hash, and bump `user_version` to 2 — all
         * before Room's own open path ever runs. By the time `buildDatabase()`
         * executes, the on-disk file already matches what `@Database(version = 2)`
         * expects, so Room's open sees oldVersion == newVersion and just opens
         * normally: no onCreate/onUpgrade, no data loss, nothing destructive.
         *
         * Safety property if the `filesDir`-derived path guess is somehow wrong on
         * some device/Android version: `dbFile.exists()` comes back false (or the
         * open/patch throws, caught below), this becomes a no-op, and behavior
         * falls back to exactly the pre-existing, already-understood failure mode
         * (Room throws its own "migration missing" error on open) — never something
         * new or silently corrupting. NOT verified against the real Light Phone
         * III's actual v1 database file — flagged in the handoff report.
         */
        private fun migrateV1ToV2IfNeeded(lightContext: SealedLightContext) {
            try {
                val dbFile = File(lightContext.filesDir.parentFile, "databases/$DB_FILE_NAME")
                if (!dbFile.exists()) return // fresh install — Room creates everything at v2 itself, nothing to migrate

                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
                db.use {
                    if (it.version != 1) return@use // already migrated, or a version this code doesn't know about — leave it alone
                    it.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `songCount` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    it.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` TEXT NOT NULL, `position` INTEGER NOT NULL, `songId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `position`))",
                    )
                    it.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    it.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V2_IDENTITY_HASH')")
                    it.version = 2
                }
            } catch (e: Exception) {
                // Swallow rather than crash from inside our own pre-flight step —
                // worst case we fall through to Room's own (already-flagged, already
                // understood) failure mode below, never a new one.
                android.util.Log.e("MusicPlusDatabase", "v1 -> v2 hand migration failed, falling back to Room's own open", e)
                AppLogger.e("MusicPlusDatabase", "v1 -> v2 hand migration failed, falling back to Room's own open", e)
            }
        }
    }
}
