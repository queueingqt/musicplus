package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.PlaylistRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistPickerScreenViewModel(
    private val playlistRepository: PlaylistRepository,
) : LightViewModel<Unit>() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { playlistRepository.refreshPlaylists() }
    }

    suspend fun addToExisting(playlistId: String, songId: String) {
        playlistRepository.addTrack(playlistId, songId)
    }

    /** Returns the new playlist's id, or null if creation failed (offline/not configured). */
    suspend fun createAndAdd(name: String, songId: String): String? {
        val id = playlistRepository.createPlaylist(name) ?: return null
        playlistRepository.addTrack(id, songId)
        playlistRepository.refreshPlaylists()
        return id
    }
}

/**
 * Minimal "add to playlist" picker for track rows in AlbumDetailScreen/
 * SearchScreen/FavoritesScreen (issue #5, item 5). Documented choice: a small
 * dedicated screen rather than reusing PlaylistListScreen in a "pick mode" —
 * PlaylistListScreen already owns its own forward-navigation (into
 * PlaylistDetailScreen), search-filter state, and "new playlist" flow; threading a
 * second pick-mode behavior through all of that for every row's onClick would cost
 * more than this file, which is a simple flat list + one "new playlist" row.
 * Tapping a row (existing or new) adds the track immediately and navigates back —
 * no further confirmation UI, matching the rest of this feature's "make it work,
 * keep it small" first pass.
 */
class PlaylistPickerScreen(
    activity: SealedLightActivity,
    private val songId: String,
) : LightScreen<Unit, PlaylistPickerScreenViewModel>(activity) {

    override val viewModelClass = PlaylistPickerScreenViewModel::class.java

    override fun createViewModel() = PlaylistPickerScreenViewModel(AppGraph.from(lightContext).playlistRepository)

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val playlists by viewModel.playlists.collectAsState()

        LightwaveScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Add to playlist"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            LightText(
                text = "New playlist",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        navigateTo({ a -> TextEditScreen(a, "Playlist name", "") }) { name ->
                            if (!name.isNullOrBlank()) {
                                scope.launch {
                                    viewModel.createAndAdd(name, songId)
                                    goBack()
                                }
                            }
                        }
                    }
                    .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
            )

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                items(playlists, key = { it.id }) { playlist ->
                    LightText(
                        text = playlist.name,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                scope.launch {
                                    viewModel.addToExisting(playlist.id, songId)
                                    goBack()
                                }
                            }
                            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
