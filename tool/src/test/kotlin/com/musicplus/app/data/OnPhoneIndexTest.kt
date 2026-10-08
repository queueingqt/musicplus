package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the phone holds audio for, worked out for a whole library at once (#80). */
class OnPhoneIndexTest {
    private fun track(id: String, album: String? = null, artist: String? = null, status: DownloadStatus? = null) =
        Track(id, id, album, null, artist, null, null, 100, null, false, status, null)

    private fun album(id: String, artist: String?) = Album(id, id, artist, null, null, 1, 100, null, false)

    @Test
    fun aFinishedDownloadCountsButAQueuedOrFailedOneDoesNot() {
        val tracks = listOf(
            track("s:done", status = DownloadStatus.COMPLETE), track("s:queued", status = DownloadStatus.QUEUED),
            track("s:going", status = DownloadStatus.DOWNLOADING), track("s:failed", status = DownloadStatus.FAILED), track("s:none"),
        )
        assertEquals(setOf("s:done"), buildOnPhoneIndex(tracks, emptyList(), emptySet(), emptyList()).songs)
    }

    @Test
    fun aKeptStreamCopyCountsByTheSongsFileKey() {
        val tracks = listOf(track("srv:a1"), track("srv:a2"))
        assertEquals(setOf("srv:a1"), buildOnPhoneIndex(tracks, emptyList(), setOf("srv_a1"), emptyList()).songs)
    }

    @Test
    fun anAlbumAndAnArtistAreOnThePhoneWhenAnyOfTheirSongsIs() {
        val tracks = listOf(track("s:1", album = "s:A", artist = "s:X", status = DownloadStatus.COMPLETE), track("s:2", album = "s:A", artist = "s:X"), track("s:3", album = "s:B", artist = "s:Y"))
        val index = buildOnPhoneIndex(tracks, emptyList(), emptySet(), emptyList())
        assertEquals(setOf("s:A"), index.albums)
        assertEquals(setOf("s:X"), index.artists)
    }

    @Test
    fun anAlbumsOwnArtistCountsWhenTheAlbumDoes() {
        val tracks = listOf(track("s:1", album = "s:A", artist = "s:Guest", status = DownloadStatus.COMPLETE))
        val index = buildOnPhoneIndex(tracks, listOf(album("s:A", "s:Various"), album("s:B", "s:Other")), emptySet(), emptyList())
        assertEquals(setOf("s:Guest", "s:Various"), index.artists)
    }

    @Test
    fun aPlaylistIsOnThePhoneWhenAnySongIsEvenOneTheTrackTableDoesNotList() {
        val tracks = listOf(track("s:1", status = DownloadStatus.COMPLETE))
        val memberships = listOf(
            PlaylistMembership("s:P1", "s:1"), PlaylistMembership("s:P2", "s:2"), PlaylistMembership("s:P3", "s:unlisted"),
        )
        val index = buildOnPhoneIndex(tracks, emptyList(), setOf("s_unlisted"), memberships)
        assertEquals(setOf("s:P1", "s:P3"), index.playlists)
    }

    @Test
    fun eachPlaylistRemembersTheServersItsSongsBelongTo() {
        val memberships = listOf(PlaylistMembership("phone:1", "a:1"), PlaylistMembership("phone:1", "b:1"), PlaylistMembership("phone:1", "a:2"))
        assertEquals(mapOf("phone:1" to setOf("a", "b")), buildOnPhoneIndex(emptyList(), emptyList(), emptySet(), memberships).playlistServers)
    }

    @Test
    fun anEmptyLibraryHoldsNothing() {
        val index = buildOnPhoneIndex(emptyList(), emptyList(), emptySet(), emptyList())
        assertTrue(index.songs.isEmpty() && index.albums.isEmpty() && index.artists.isEmpty() && index.playlists.isEmpty())
    }
}
