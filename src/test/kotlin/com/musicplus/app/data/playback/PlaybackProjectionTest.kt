package com.musicplus.app.data.playback

import com.musicplus.app.Track
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one projection of the app's decisions and the player's live values into what a screen shows (#63). */
class PlaybackProjectionTest {
    private fun track(n: Int, durationSec: Int = 200) = Track(
        id = "a:$n", title = "t$n", albumId = null, albumName = null, artistId = null, artistName = null, trackNumber = null,
        durationSec = durationSec, coverArtUrl = null, isFavorite = false, downloadStatus = null, localFilePath = null,
    )

    private val queue = List(5) { track(it) }

    private fun session(
        hold: Hold, pending: Int = 2, loading: Boolean = false, holding: Boolean? = null,
        streaming: Set<String> = emptySet(), loadError: String? = null,
    ) = Session(queue = queue, pendingIndex = pending, hold = hold, loading = loading, holdingPlaying = holding, streaming = streaming, loadError = loadError)

    private fun player(index: Int = 0, playing: Boolean = false, position: Long = 0, duration: Long = 0, error: LightAudioError? = null) =
        PlayerReading(index, playing, position, duration, error)

    @Test
    fun aRestoredQueueShowsItsSongPausedAtZeroAndIsNotLoading() {
        // The player's own values still describe whatever it held before (its index 0 is in range by luck).
        val s = project(session(Hold.NOTHING, pending = 3), player(index = 0, playing = true, position = 99_000, duration = 180_000))
        assertEquals(3, s.currentIndex)
        assertFalse(s.isPlaying)
        assertEquals(0L, s.positionMs)
        assertFalse(s.isLoading, "restored is waiting for play, not loading (#63)")
        assertEquals(200_000L, s.durationMs, "the library's length while the player's is not the song's")
    }

    @Test
    fun aPlayThatIsLoadingIsLoadingWhetherOrNotThePlayerHoldsAnythingYet() {
        assertTrue(project(session(Hold.NOTHING, loading = true), player()).isLoading)
        assertTrue(project(session(Hold.PARTIAL, loading = true), player(playing = false)).isLoading)
        assertFalse(project(session(Hold.PARTIAL, loading = true), player(playing = true)).isLoading, "audio has started")
    }

    @Test
    fun whileOnlyPartOfTheQueueIsHeldThePlayersOwnIndexIsNotTheQueuesButItsPlaybackIsReal() {
        val s = project(session(Hold.PARTIAL, pending = 3), player(index = 0, playing = true, position = 12_000, duration = 150_000))
        assertEquals(3, s.currentIndex)
        assertTrue(s.isPlaying)
        assertEquals(12_000L, s.positionMs)
        assertEquals(150_000L, s.durationMs)
    }

    @Test
    fun onceTheWholeQueueIsHeldThePlayersIndexIsTheQueuesIndex() {
        val s = project(session(Hold.WHOLE, pending = 3), player(index = 1, playing = true, position = 5_000, duration = 100_000))
        assertEquals(1, s.currentIndex)
        assertEquals("t1", s.currentTrack?.title)
    }

    @Test
    fun anIndexOutsideTheQueueFallsBackToThePendingOneAndIsPending() {
        val s = project(session(Hold.WHOLE, pending = 4), player(index = 9, playing = true, position = 5_000))
        assertEquals(4, s.currentIndex)
        assertFalse(s.isPlaying)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun aHandOverHoldsTheIconWhateverThePlayerReports() {
        assertTrue(project(session(Hold.PARTIAL, holding = true), player(playing = false)).isPlaying)
        assertFalse(project(session(Hold.PARTIAL, holding = false), player(playing = true)).isPlaying)
    }

    @Test
    fun aStreamThePlayerCannotSeekInIsNotSeekable() {
        assertFalse(project(session(Hold.WHOLE, streaming = setOf("a:1")), player(index = 1)).canSeek)
        assertTrue(project(session(Hold.WHOLE, streaming = setOf("a:1")), player(index = 2)).canSeek)
    }

    @Test
    fun aPlayerErrorWinsOverALoadErrorAndEitherIsShown() {
        val error = LightAudioError(LightAudioErrorKind.Source, "ERROR_CODE_IO_UNSPECIFIED", 1)
        assertEquals("Source: ERROR_CODE_IO_UNSPECIFIED", project(session(Hold.WHOLE, loadError = "later"), player(index = 1, error = error)).errorMessage)
        assertEquals("Couldn't load", project(session(Hold.NOTHING, loadError = "Couldn't load"), player()).errorMessage)
    }
}
