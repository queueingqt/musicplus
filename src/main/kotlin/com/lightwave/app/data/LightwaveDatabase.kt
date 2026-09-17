package com.lightwave.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        DownloadEntity::class,
        QueueItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LightwaveDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun queueDao(): QueueDao

    companion object {
        // `buildDatabase` is the SDK's Room-builder extension on SealedLightContext
        // (sdk/client/.../LightDb.kt) — routes storage through the sandboxed app
        // context LightOS expects rather than a raw Context.
        fun create(lightContext: SealedLightContext): LightwaveDatabase =
            lightContext.buildDatabase(LightwaveDatabase::class.java, "lightwave.db")
    }
}
