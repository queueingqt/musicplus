package com.musicplus.app

import com.musicplus.app.data.LibraryState
import com.musicplus.app.data.ServerScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** The warning before an irreversible delete, word for word: counts, plurals and what it says is lost. */
class ClearDataWarningTest {
    private val base = "Removes downloaded music, cached artwork and lyrics, and the library cache. Your server login stays saved."

    @Test
    fun withNothingThatExistsOnlyOnThePhoneItStatesJustWhatGoes() {
        assertEquals(base, clearAllDataMessage(0, 0, 0))
    }

    @Test
    fun aSinglePhoneOnlyPlaylistIsNamedInTheSingularWithItsOwnVerb() {
        assertEquals("This permanently deletes 1 Phone Only playlist. It exists nowhere else. $base", clearAllDataMessage(1, 0, 0))
    }

    @Test
    fun aSingleKeptHeartIsNamedInTheSingular() {
        assertEquals("This permanently deletes 1 favorite kept only on this phone. It exists nowhere else. $base", clearAllDataMessage(0, 1, 0))
    }

    @Test
    fun severalOfEachArePluralAndJoined() {
        assertEquals(
            "This permanently deletes 3 Phone Only playlists and 5 favorites kept only on this phone. They exist nowhere else. $base",
            clearAllDataMessage(3, 5, 0),
        )
    }

    @Test
    fun oneOfEachIsStillPluralForTheVerbBecauseThereAreTwoThings() {
        assertEquals(
            "This permanently deletes 1 Phone Only playlist and 1 favorite kept only on this phone. They exist nowhere else. $base",
            clearAllDataMessage(1, 1, 0),
        )
    }

    @Test
    fun changesWaitingToSyncAreSaidToBeLost() {
        assertEquals("$base 1 change waiting to sync will be lost.", clearAllDataMessage(0, 0, 1))
        assertEquals("$base 4 changes waiting to sync will be lost.", clearAllDataMessage(0, 0, 4))
    }

    @Test
    fun theOnlyUnrecoverableThingsAreNamedFirstAndTheSyncLossComesLast() {
        val message = clearAllDataMessage(2, 0, 2)
        assertEquals("This permanently deletes 2 Phone Only playlists. They exist nowhere else. $base 2 changes waiting to sync will be lost.", message)
    }

    @Test
    fun theCountsComeFromWhatIsCachedOnThePhone() {
        val library = LibraryState()
        fun playlist(id: String) = Playlist(id, "p", 0, 0)
        library.playlists.set(listOf(playlist("srv:1"), playlist("${ServerScope.PHONE}:a"), playlist("${ServerScope.PHONE}:b")))
        assertEquals(2, library.phoneOnlyPlaylistCount(), "only the ones living on the phone")
        assertEquals(0, library.phoneOnlyFavoriteCount())
    }
}
