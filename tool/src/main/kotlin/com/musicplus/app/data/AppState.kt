package com.musicplus.app.data

import androidx.compose.runtime.staticCompositionLocalOf
import com.musicplus.app.Track
import kotlinx.coroutines.flow.StateFlow

/** The warm copy of each setting a screen shows (see [Setting.warm]); the defaults are the app's own, a test builds its own. */
class SettingsState(
    val showAlbumArtwork: WarmedFlow<Boolean> = Settings.showAlbumArtwork.warm,
    val downloadedOnly: WarmedFlow<Boolean> = Settings.downloadedOnly.warm,
    val scrobbling: WarmedFlow<Boolean> = Settings.scrobbling.warm,
    val streamQualityWifi: WarmedFlow<Int?> = Settings.streamQualityWifi.warm,
    val streamQualityCellular: WarmedFlow<Int?> = Settings.streamQualityCellular.warm,
    val downloadQuality: WarmedFlow<Int?> = Settings.downloadQuality.warm,
)

/** What a row needs to know about the audio the phone holds, apart from the servers. */
interface TrackAudio {
    /** Changes when the copies kept from streaming change. */
    val streamCopies: StateFlow<Int>

    fun hasAudioOnPhone(track: Track): Boolean
}

/** [TrackAudio] over the app's [TrackAvailability]. */
object TrackAvailabilityAudio : TrackAudio {
    override val streamCopies: StateFlow<Int> get() = TrackAvailability.streamCopies
    override fun hasAudioOnPhone(track: Track): Boolean = TrackAvailability.hasAudioOnPhone(track)
}

/**
 * Everything a screen reads from the app's warm state (the saved servers, the cached library, which rows can be played, the displayed settings,
 * the last scrobble error, the audio on the phone), in one place that is handed to it. Screens read their state through this instead of
 * reaching for process-wide objects, so what a ViewModel or a row depends on is visible in its constructor (or, for a row with no ViewModel,
 * in `LocalAppState`), and a test gives it one of its own instead of setting globals. The defaults are the app's real, shared state
 * (see [AppServerPrefs] and its siblings, which the data layer still reads by name): handing the same instances to screens is what keeps what
 * they show from flashing a default before the real value arrives (see [WarmedFlow]).
 */
class AppState(
    val servers: ServerState = AppServerPrefs,
    val library: LibraryState = AppLibraryCache,
    val availability: AvailabilityState = AppAvailability,
    val settings: SettingsState = SettingsState(),
    val scrobbleError: WarmedFlow<String?> = AppScrobblePrefs.lastError,
    val audio: TrackAudio = TrackAvailabilityAudio,
) {
    companion object {
        /** The app's own. */
        val Default: AppState by lazy { AppState() }
    }
}

/** The [AppState] a row composable reads. The real one unless a test provides another. */
val LocalAppState = staticCompositionLocalOf { AppState.Default }
