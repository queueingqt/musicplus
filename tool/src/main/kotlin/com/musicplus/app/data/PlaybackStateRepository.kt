package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.musicplus.app.RepeatMode
import kotlinx.coroutines.flow.first

/**
 * Scalar "now playing" resume state — current queue index, playback position,
 * shuffle/repeat mode — so a restarted process can
 * restore what was playing (issue #27). The queue's own song-id order lives in
 * Room ([QueueDao]) instead, since that's genuinely a list, not a handful of
 * scalars; this only holds the small values that go with it. Backed by the
 * same shared `DataStore<Preferences>` as [AppSettingsRepository]/
 * [ServerConfigRepository], just a different key namespace.
 */
class PlaybackStateRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val CURRENT_INDEX = intPreferencesKey("playback_current_index")
        val POSITION_MS = longPreferencesKey("playback_position_ms")
        val SHUFFLE = booleanPreferencesKey("playback_shuffle")
        val REPEAT_MODE = stringPreferencesKey("playback_repeat_mode")

        /** No longer saved (#77: a saved hint cannot say which song it was for); removed from what earlier versions left behind. */
        val LEGACY_ALBUM_ART_URL = stringPreferencesKey("playback_album_art_url")
    }

    data class Saved(
        val currentIndex: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: RepeatMode,
    )

    suspend fun read(): Saved {
        val prefs = dataStore.data.first()
        return Saved(
            currentIndex = prefs[Keys.CURRENT_INDEX] ?: 0,
            positionMs = prefs[Keys.POSITION_MS] ?: 0L,
            shuffle = prefs[Keys.SHUFFLE] ?: false,
            // Falls back to OFF for an unrecognized/corrupt saved name rather than
            // throwing — a bad resume value should never be the reason playback
            // itself fails to restore.
            repeatMode = prefs[Keys.REPEAT_MODE]?.let { name -> runCatching { RepeatMode.valueOf(name) }.getOrNull() } ?: RepeatMode.OFF,
        )
    }

    suspend fun save(saved: Saved) {
        dataStore.edit { prefs ->
            prefs[Keys.CURRENT_INDEX] = saved.currentIndex
            prefs[Keys.POSITION_MS] = saved.positionMs
            prefs[Keys.SHUFFLE] = saved.shuffle
            prefs[Keys.REPEAT_MODE] = saved.repeatMode.name
            prefs.remove(Keys.LEGACY_ALBUM_ART_URL)
        }
    }

    /** Used by LocalDataRepository.clearAll() — the persisted queue this resume state points at is being wiped too, so there's nothing left for it to meaningfully resume. */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.CURRENT_INDEX)
            prefs.remove(Keys.POSITION_MS)
            prefs.remove(Keys.SHUFFLE)
            prefs.remove(Keys.REPEAT_MODE)
            prefs.remove(Keys.LEGACY_ALBUM_ART_URL)
        }
    }
}
