package com.musicplus.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import java.io.File

/**
 * Gates [MusicPlusDatabase.create] to its one legitimate caller, `AppGraph.build()`.
 *
 * `create()` builds a brand-new Room instance every time it's called —
 * Room's Flow invalidation tracking is per-*instance*, not per underlying
 * file, so a write through a second instance never notifies Flow
 * observers the app's already-open (AppGraph-owned) instance is serving.
 * Both `@LightJob` handlers this tool ships (DownloadRepository.
 * downloadTrack, SyncQueueRepository.syncPendingMutations) hit exactly
 * this bug by calling `create()` directly, before being fixed by hand to
 * go through `AppGraph.from(lightContext).database` instead — see their
 * doc comments. Nothing stopped a third `@LightJob` handler from making
 * the identical mistake as long as `create()` stayed a plain callable
 * function, since a doc comment only helps a contributor who reads it.
 *
 * Plain `internal` visibility wouldn't have closed that gap: this whole
 * tool ships as a single Gradle module (dropped into light-sdk's `tool/`
 * slot as-is — see build.gradle.kts's header comment), so every file
 * under `com.musicplus.app`, including a hypothetical third job handler,
 * already compiles into the same module `internal` would scope `create()`
 * to. This opt-in requirement is what actually gates it instead: calling
 * `create()` from anywhere not explicitly annotated
 * `@OptIn(DatabaseFactoryAccess::class)` is a compile error, not just an
 * unread comment. `AppGraph.build()` is the only place that opts in.
 *
 * Declared top-level (not nested inside [MusicPlusDatabase]'s companion
 * object) deliberately — Kotlin doesn't let a companion-nested type be
 * referenced as `Outer.Nested`, only as `Outer.Companion.Nested`, which
 * would have made every `@OptIn` site spell out `.Companion.` for no
 * reason.
 */
@RequiresOptIn(
    message = "MusicPlusDatabase.create() builds an uncoordinated second Room " +
        "instance whose Flow invalidation is invisible to the rest of the app " +
        "(see this annotation's doc). Use AppGraph.from(lightContext).database " +
        "instead — that's the shared, already-open instance every screen and " +
        "@LightJob handler is meant to observe and write through.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class DatabaseFactoryAccess

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        DownloadEntity::class,
        QueueItemEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PendingMutationEntity::class,
    ],
    // v2: adds playlists/playlist_tracks (issue #5). v3: adds pending_mutations
    // (issue #24, the offline sync queue). v4: adds downloads.attemptCount (a
    // download that keeps failing now gives up and surfaces FAILED instead of
    // silently retrying forever with no user-visible signal — reported live).
    // v5: no table changed — every id is now scoped to the server it came from
    // (ServerScope), so two servers' libraries can share one database.
    // See migrateV1ToV2IfNeeded/migrateV2ToV3IfNeeded/migrateV3ToV4IfNeeded/
    // migrateV4ToV5IfNeeded below for how an existing install is carried forward
    // losslessly despite there being no way to register a real Migration object here.
    version = 5,
    exportSchema = false,
)
abstract class MusicPlusDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun queueDao(): QueueDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun pendingMutationDao(): PendingMutationDao
    abstract fun serverCleanupDao(): ServerCleanupDao

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
        // Unaffected by this class's own earlier rename — Room's identity hash
        // is derived from the entity/column shape, not the class name.
        private const val V2_IDENTITY_HASH = "7ebbf7cb6e6169831fde6abd69471667"
        // Same provenance as V2_IDENTITY_HASH above — read verbatim off a real
        // build's generated MusicPlusDatabase_Impl.kt after adding
        // PendingMutationEntity, not invented.
        private const val V3_IDENTITY_HASH = "29d76f40bf75edd9fe9d5139cf3c35d2"
        // Same provenance as V2_IDENTITY_HASH above — read verbatim off a real
        // build's generated MusicPlusDatabase_Impl.kt after adding
        // DownloadEntity.attemptCount, not invented.
        private const val V4_IDENTITY_HASH = "a73549bac1003f8e97bf4f7dce702bbd"

        private const val DB_FILE_NAME = "musicplus.db"

        // `buildDatabase` is the SDK's Room-builder extension on SealedLightContext
        // (sdk/client/.../LightDb.kt) — routes storage through the sandboxed app
        // context LightOS expects rather than a raw Context.
        @DatabaseFactoryAccess
        internal fun create(lightContext: SealedLightContext, activeServerId: () -> String?): MusicPlusDatabase {
            migrateV1ToV2IfNeeded(lightContext)
            migrateV2ToV3IfNeeded(lightContext)
            migrateV3ToV4IfNeeded(lightContext)
            migrateV4ToV5IfNeeded(lightContext, activeServerId)
            return lightContext.buildDatabase(MusicPlusDatabase::class.java, DB_FILE_NAME)
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

        /**
         * Hand-rolled v2 -> v3 migration (adds `pending_mutations`, issue #24's
         * offline sync queue) — same raw-SQLite pre-flight approach as
         * migrateV1ToV2IfNeeded above, for the exact same reason (no
         * addMigrations hook exposed by this SDK's buildDatabase()). See that
         * function's doc for the full rationale; this only differs in which
         * version it patches from/to and which table it adds.
         */
        private fun migrateV2ToV3IfNeeded(lightContext: SealedLightContext) {
            try {
                val dbFile = File(lightContext.filesDir.parentFile, "databases/$DB_FILE_NAME")
                if (!dbFile.exists()) return // fresh install — Room creates everything at v3 itself, nothing to migrate

                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
                db.use {
                    if (it.version != 2) return@use // already migrated, or a version this code doesn't know about — leave it alone
                    it.execSQL(
                        "CREATE TABLE IF NOT EXISTS `pending_mutations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `targetId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `lastError` TEXT)",
                    )
                    it.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V3_IDENTITY_HASH')")
                    it.version = 3
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicPlusDatabase", "v2 -> v3 hand migration failed, falling back to Room's own open", e)
                AppLogger.e("MusicPlusDatabase", "v2 -> v3 hand migration failed, falling back to Room's own open", e)
            }
        }

        /**
         * Hand-rolled v3 -> v4 migration (adds `downloads.attemptCount`) — same
         * raw-SQLite pre-flight approach as migrateV1ToV2IfNeeded above, for the
         * exact same reason (no addMigrations hook exposed by this SDK's
         * buildDatabase()). See that function's doc for the full rationale.
         * Unlike the earlier two migrations this adds a column to an existing
         * table rather than whole new tables, so it's a single ALTER TABLE —
         * SQLite backfills the DEFAULT 0 onto every existing row automatically.
         */
        private fun migrateV3ToV4IfNeeded(lightContext: SealedLightContext) {
            try {
                val dbFile = File(lightContext.filesDir.parentFile, "databases/$DB_FILE_NAME")
                if (!dbFile.exists()) return // fresh install — Room creates everything at v4 itself, nothing to migrate

                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
                db.use {
                    if (it.version != 3) return@use // already migrated, or a version this code doesn't know about — leave it alone
                    it.execSQL("ALTER TABLE `downloads` ADD COLUMN `attemptCount` INTEGER NOT NULL DEFAULT 0")
                    it.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V4_IDENTITY_HASH')")
                    it.version = 4
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicPlusDatabase", "v3 -> v4 hand migration failed, falling back to Room's own open", e)
                AppLogger.e("MusicPlusDatabase", "v3 -> v4 hand migration failed, falling back to Room's own open", e)
            }
        }

        /**
         * v4 -> v5: scopes every id to the server it came from — see [IdScopeMigration], which holds the
         * whole procedure. Same raw-SQLite pre-flight approach as the migrations above. Unlike them it
         * rewrites live data and files, so a failure is not swallowed into Room's own open (which could
         * only complain about a missing migration): it is logged and rethrown. The database is then
         * exactly as it was, since the rewrite is one transaction, and the next launch tries again.
         * [activeServerId] is asked only when there is something to migrate.
         */
        private fun migrateV4ToV5IfNeeded(lightContext: SealedLightContext, activeServerId: () -> String?) {
            try {
                val dbFile = File(lightContext.filesDir.parentFile, "databases/$DB_FILE_NAME")
                if (!dbFile.exists()) return // fresh install — Room creates everything at v5 itself, nothing to migrate

                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
                db.use {
                    if (it.version != 4) return@use // already migrated, or a version this code doesn't know about — leave it alone
                    val serverId = activeServerId() ?: IdScopeMigration.UNASSIGNED_SERVER
                    IdScopeMigration.run(it, serverId, lightContext.filesDir, newVersion = 5)
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicPlusDatabase", "v4 -> v5 id scoping failed; the database is unchanged", e)
                AppLogger.e("MusicPlusDatabase", "v4 -> v5 id scoping failed; the database is unchanged", e)
                throw e
            }
        }
    }
}
