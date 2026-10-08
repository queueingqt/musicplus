package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One setting, declared once: where it is stored, what it is when nothing was stored, and the warm copy a screen reads first (see
 * [WarmedFlow]: a fresh screen would otherwise flash the default before the real value arrives). The key, the default and the warm copy used to
 * be three places (the repository, a `*Prefs` object, a line in AppGraph) with the default written in two of them.
 */
class Setting<T> private constructor(
    private val read: (Preferences) -> T,
    private val write: (MutablePreferences, T) -> Unit,
    val default: T,
) {
    /** What screens read first; kept current by [mirror]. */
    val warm = WarmedFlow(default)

    fun observe(dataStore: DataStore<Preferences>): Flow<T> = dataStore.data.map(read)

    suspend fun set(dataStore: DataStore<Preferences>, value: T) {
        dataStore.edit { write(it, value) }
    }

    /** Keeps [warm] in step with what is stored, for the life of [scope]. */
    fun mirror(dataStore: DataStore<Preferences>, scope: CoroutineScope) {
        scope.launch { observe(dataStore).retryOnTransientDbError().collect { warm.set(it) } }
    }

    companion object {
        /** A switch. An absent key means "not yet set", which is [default], not off. */
        fun flag(name: String, default: Boolean): Setting<Boolean> {
            val key = booleanPreferencesKey(name)
            return Setting({ it[key] ?: default }, { prefs, value -> prefs[key] = value }, default)
        }

        /**
         * A bit-rate cap in kbps, or null for the original (uncapped). DataStore has no real null, so "original" is stored as 0, which is
         * never a bit-rate anyone would ask for; an absent key is [default].
         */
        fun kbps(name: String, default: Int?): Setting<Int?> {
            val key = intPreferencesKey(name)
            return Setting(
                read = { prefs -> when (val stored = prefs[key]) { null -> default; ORIGINAL -> null; else -> stored } },
                write = { prefs, value -> prefs[key] = value ?: ORIGINAL },
                default = default,
            )
        }

        private const val ORIGINAL = 0
    }
}

/** Every setting a screen shows or the app acts on, each declared once. */
object Settings {
    /** On by default (issue #9 amendment). */
    val showAlbumArtwork = Setting.flag("show_album_artwork", default = true)

    /** Off by default: every list shows the whole library. On, only what the phone holds audio for (see [ListAvailability]), for being online but not wanting to stream. */
    val downloadedOnly = Setting.flag("downloaded_only", default = false)

    /** Off by default: it sends personal listening history to a linked Last.fm/ListenBrainz account (relayed by the server itself), so it is opt-in. */
    val scrobbling = Setting.flag("scrobbling_enabled", default = false)

    /** 320 kbps on Wi-Fi, where bandwidth is not the constraint; this is what streaming did before quality was configurable. */
    val streamQualityWifi = Setting.kbps("stream_quality_wifi_kbps", default = 320)

    /** 192 kbps on cellular, deliberately lower: cellular data is more often limited or metered. */
    val streamQualityCellular = Setting.kbps("stream_quality_cellular_kbps", default = 192)

    /** Original by default: tapping Download already says "I want this offline", so the file's own quality is kept unless told otherwise. */
    val downloadQuality = Setting.kbps("download_quality_kbps", default = null)

    val all: List<Setting<*>> = listOf(showAlbumArtwork, downloadedOnly, scrobbling, streamQualityWifi, streamQualityCellular, downloadQuality)

    /** Starts keeping every setting's warm copy current. */
    fun mirrorAll(dataStore: DataStore<Preferences>, scope: CoroutineScope) {
        all.forEach { it.mirror(dataStore, scope) }
    }
}
