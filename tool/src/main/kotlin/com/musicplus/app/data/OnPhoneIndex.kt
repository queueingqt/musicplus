package com.musicplus.app.data

import com.musicplus.app.Album
import com.musicplus.app.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * What the phone holds audio for, as sets of scoped ids: a song whose finished download or kept stream copy is on the phone, and
 * the albums, artists and playlists that have at least one such song (a half-downloaded album counts). This is the one place the
 * question "does this play with no server?" is answered for a whole list, where [TrackAvailability.hasAudioOnPhone] answers it for
 * one song.
 *
 * A song's own audio is a finished download ([DownloadStatus.COMPLETE]) or a copy in the stream cache; the file itself is not
 * re-checked here (that is [MediaIntegrity]'s job, and a list must not touch the disk for every row).
 */
class OnPhoneIndex(
    val songs: Set<String>,
    val albums: Set<String>,
    val artists: Set<String>,
    val playlists: Set<String>,
    /** For each playlist that has songs: the servers those songs belong to, so a Phone Only playlist can tell whether any of its songs can be reached. */
    val playlistServers: Map<String, Set<String>>,
) {
    companion object {
        val EMPTY = OnPhoneIndex(emptySet(), emptySet(), emptySet(), emptySet(), emptyMap())
    }
}

/**
 * [tracks] is every cached song, [albums] every cached album (an album's artist counts when the album does, as a compilation's tracks
 * name other artists), [copyKeys] is [com.musicplus.app.data.playback.StreamCache.copyKeys] and [memberships] every playlist's songs.
 */
fun buildOnPhoneIndex(tracks: List<Track>, albums: List<Album>, copyKeys: Set<String>, memberships: List<PlaylistMembership>): OnPhoneIndex {
    fun hasCopy(songId: String) = copyKeys.isNotEmpty() && ServerScope.fileKey(songId) in copyKeys

    val withAudio = tracks.filter { it.downloadStatus == DownloadStatus.COMPLETE || hasCopy(it.id) }
    val songs = withAudio.mapTo(HashSet()) { it.id }
    val albumIds = withAudio.mapNotNullTo(HashSet()) { it.albumId }
    val artistIds = withAudio.mapNotNullTo(HashSet()) { it.artistId }
    albums.forEach { album -> if (album.id in albumIds) album.artistId?.let(artistIds::add) }

    val playlistsWithAudio = HashSet<String>()
    val playlistServers = HashMap<String, MutableSet<String>>()
    for ((playlistId, songId) in memberships) {
        // A member the track table does not list can still have a copy on the phone.
        if (songId in songs || hasCopy(songId)) playlistsWithAudio += playlistId
        ServerScope.serverOf(songId)?.let { playlistServers.getOrPut(playlistId) { HashSet() } += it }
    }
    return OnPhoneIndex(songs, albumIds, artistIds, playlistsWithAudio, playlistServers)
}

/** [buildOnPhoneIndex] kept current as any of its inputs change, computed off the main thread (it walks the whole library). */
fun observeOnPhoneIndex(
    tracks: Flow<List<Track>>,
    albums: Flow<List<Album>>,
    copyKeys: Flow<Set<String>>,
    memberships: Flow<List<PlaylistMembership>>,
): Flow<OnPhoneIndex> =
    combine(tracks, albums, copyKeys, memberships, ::buildOnPhoneIndex).conflate().flowOn(Dispatchers.Default)
