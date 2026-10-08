package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Artist
import com.musicplus.app.Playlist
import com.musicplus.app.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which rows of a list can be played right now, and so where they go (#80). Two servers: `up` and `down`.
 */
class ListAvailabilityTest {
    private fun track(id: String, album: String? = null, artist: String? = null, status: DownloadStatus? = null) =
        Track(id, id, album, null, artist, null, null, 100, null, false, status, null)

    private fun album(id: String, artist: String? = null) = Album(id, id, artist, null, null, 1, 100, null, false)
    private fun artist(id: String) = Artist(id, id, null, 1, false)
    private fun playlist(id: String) = Playlist(id, id, 1, 100)

    private val allUp = ServersNow(anyKnown = true, enabled = setOf("up", "down"), unreachable = emptySet())
    private val oneDown = ServersNow(anyKnown = true, enabled = setOf("up", "down"), unreachable = setOf("down"))

    private fun availability(
        servers: ServersNow,
        tracks: List<Track> = emptyList(),
        albums: List<Album> = emptyList(),
        copyKeys: Set<String> = emptySet(),
        memberships: List<PlaylistMembership> = emptyList(),
        downloadedOnly: Boolean = false,
    ) = ListAvailability(buildOnPhoneIndex(tracks, albums, copyKeys, memberships), servers, downloadedOnly)

    private val songs = listOf(
        track("down:1"), track("up:1"), track("down:2", status = DownloadStatus.COMPLETE), track("up:2"), track("down:3"),
    )

    @Test
    fun whileEveryServerIsUpAListKeepsItsOrderAndNothingIsUnavailable() {
        val split = availability(allUp, tracks = songs).songs(songs)
        assertEquals(songs, split.playable)
        assertTrue(split.unavailable.isEmpty())
    }

    @Test
    fun withOneServerDownItsUndownloadedRowsSinkAndTheRestKeepTheirOrder() {
        val split = availability(oneDown, tracks = songs).songs(songs)
        // down:2 is downloaded, so it stays with the playable ones, in its place among them.
        assertEquals(listOf("up:1", "down:2", "up:2"), split.playable.map { it.id })
        assertEquals(listOf("down:1", "down:3"), split.unavailable.map { it.id })
    }

    @Test
    fun aCopyKeptFromStreamingPlaysWithNoServerToo() {
        val split = availability(oneDown, tracks = songs, copyKeys = setOf("down_3")).songs(songs)
        assertEquals(listOf("up:1", "down:2", "up:2", "down:3"), split.playable.map { it.id })
        assertEquals(listOf("down:1"), split.unavailable.map { it.id })
    }

    @Test
    fun anAlbumWithSomeSongsOnThePhoneIsPlayableWhenItsServerIsDown() {
        val tracks = listOf(track("down:a", album = "down:A1", status = DownloadStatus.COMPLETE), track("down:b", album = "down:A1"), track("down:c", album = "down:A2"))
        val albums = listOf(album("down:A2"), album("down:A1"), album("up:A3"))
        val split = availability(oneDown, tracks, albums).albums(albums)
        assertEquals(listOf("down:A1", "up:A3"), split.playable.map { it.id })
        assertEquals(listOf("down:A2"), split.unavailable.map { it.id })
    }

    @Test
    fun anArtistIsPlayableThroughAnySongOrAnAlbumItMade() {
        val tracks = listOf(track("down:a", album = "down:A1", artist = "down:X", status = DownloadStatus.COMPLETE), track("down:b", artist = "down:Y"))
        // A compilation: the downloaded song names another artist, but the album is by Various.
        val albums = listOf(album("down:A1", artist = "down:Various"))
        val artists = listOf(artist("down:X"), artist("down:Y"), artist("down:Various"), artist("up:Z"))
        val split = availability(oneDown, tracks, albums).artists(artists)
        assertEquals(listOf("down:X", "down:Various", "up:Z"), split.playable.map { it.id })
        assertEquals(listOf("down:Y"), split.unavailable.map { it.id })
    }

    @Test
    fun aPlaylistOnADownServerNeedsASongOnThePhoneAndOneOnAnUpServerDoesNot() {
        val tracks = listOf(track("down:a", status = DownloadStatus.COMPLETE), track("down:b"))
        val memberships = listOf(PlaylistMembership("down:P1", "down:a"), PlaylistMembership("down:P2", "down:b"), PlaylistMembership("up:P3", "up:x"))
        val lists = listOf(playlist("down:P2"), playlist("down:P1"), playlist("up:P3"))
        val split = availability(oneDown, tracks, memberships = memberships).playlists(lists)
        assertEquals(listOf("down:P1", "up:P3"), split.playable.map { it.id })
        assertEquals(listOf("down:P2"), split.unavailable.map { it.id })
    }

    @Test
    fun aPhoneOnlyPlaylistFollowsItsSongs() {
        val memberships = listOf(
            PlaylistMembership("phone:1", "down:a"), PlaylistMembership("phone:1", "up:a"),  // one reachable song
            PlaylistMembership("phone:2", "down:b"),                                        // only an unreachable one
        )
        val lists = listOf(playlist("phone:1"), playlist("phone:2"), playlist("phone:3"))   // phone:3 has no songs
        val split = availability(oneDown, memberships = memberships).playlists(lists)
        assertEquals(listOf("phone:1", "phone:3"), split.playable.map { it.id })
        assertEquals(listOf("phone:2"), split.unavailable.map { it.id })
    }

    @Test
    fun aPhoneOnlyPlaylistWithADownloadedSongIsPlayableEvenWhenItsServerIsDown() {
        val tracks = listOf(track("down:b", status = DownloadStatus.COMPLETE))
        val memberships = listOf(PlaylistMembership("phone:2", "down:b"))
        val split = availability(oneDown, tracks, memberships = memberships).playlists(listOf(playlist("phone:2")))
        assertEquals(listOf("phone:2"), split.playable.map { it.id })
    }

    @Test
    fun aServerThatIsOffCountsAsUnusableLikeOneThatCannotBeReached() {
        val off = ServersNow(anyKnown = true, enabled = setOf("up"), unreachable = emptySet())
        val split = availability(off, tracks = songs).songs(songs)
        assertEquals(listOf("up:1", "down:2", "up:2"), split.playable.map { it.id })
    }

    @Test
    fun nothingIsPutBelowTheLineBeforeTheServersAreKnown() {
        val unknown = ServersNow(anyKnown = false, enabled = emptySet(), unreachable = setOf("down"))
        assertEquals(songs, availability(unknown, tracks = songs).songs(songs).playable)
    }

    @Test
    fun downloadedOnlyShowsJustWhatIsOnThePhoneWhateverTheServersAreDoing() {
        val onlyWhatIsOnThePhone = listOf(track("down:2", status = DownloadStatus.COMPLETE))
        for (servers in listOf(allUp, oneDown)) {
            val split = availability(servers, tracks = songs, downloadedOnly = true).songs(songs)
            assertEquals(onlyWhatIsOnThePhone.map { it.id }, split.playable.map { it.id })
            assertTrue(split.unavailable.isEmpty(), "no line below: nothing unavailable is shown")
        }
    }

    @Test
    fun downloadedOnlyAppliesToAlbumsArtistsAndPlaylistsToo() {
        val tracks = listOf(track("up:a", album = "up:A1", artist = "up:X", status = DownloadStatus.COMPLETE), track("up:b", album = "up:A2", artist = "up:Y"))
        val memberships = listOf(PlaylistMembership("up:P1", "up:a"), PlaylistMembership("up:P2", "up:b"), PlaylistMembership("phone:E", "up:c"))
        val a = availability(allUp, tracks, memberships = memberships, downloadedOnly = true)
        assertEquals(listOf("up:A1"), a.albums(listOf(album("up:A1"), album("up:A2"))).playable.map { it.id })
        assertEquals(listOf("up:X"), a.artists(listOf(artist("up:X"), artist("up:Y"))).playable.map { it.id })
        assertEquals(listOf("up:P1"), a.playlists(listOf(playlist("up:P1"), playlist("up:P2"), playlist("phone:E"))).playable.map { it.id })
    }

    @Test
    fun theLineOnlyShowsWhenSomeRowsCanBePlayedAndSomeCannot() {
        assertTrue(AvailableSplit(listOf(1), listOf(2)).hasLine)
        assertTrue(!AvailableSplit(listOf(1), emptyList()).hasLine, "nothing unavailable: no line")
        assertTrue(!AvailableSplit(emptyList(), listOf(2)).hasLine, "nothing playable: nothing above the line to tell them from")
        assertTrue(!AvailableSplit.empty<Int>().hasLine)
    }

    @Test
    fun aRowMovesBackAboveTheLineWhenItsServerComesBack() {
        val down = availability(oneDown, tracks = songs).songs(songs)
        val back = availability(allUp, tracks = songs).songs(songs)
        assertEquals(2, down.unavailable.size)
        assertEquals(songs, back.playable)
    }
}
