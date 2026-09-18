package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistListScreenViewModel(
    private val playlistRepository: PlaylistRepository,
) : LightViewModel<Unit>() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val allPlaylists = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Client-side filter, same reasoning as AlbumListScreen/SearchScreen: no
    // server-side playlist name search in the Subsonic API worth round-tripping for
    // what's realistically a short list.
    val playlists: StateFlow<List<Playlist>> = combine(allPlaylists, _query) { playlists, q ->
        if (q.isBlank()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { playlistRepository.refreshPlaylists() }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** Returns the new playlist's id, or null if creation failed (offline/not configured). */
    suspend fun createPlaylist(name: String): String? {
        val id = playlistRepository.createPlaylist(name)
        playlistRepository.refreshPlaylists()
        return id
    }
}

class PlaylistListScreen(activity: SealedLightActivity) :
    LightScreen<Unit, PlaylistListScreenViewModel>(activity) {

    override val viewModelClass = PlaylistListScreenViewModel::class.java

    override fun createViewModel() = PlaylistListScreenViewModel(AppGraph.from(lightContext).playlistRepository)

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val query by viewModel.query.collectAsState()
        val playlists by viewModel.playlists.collectAsState()

        LightwaveScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Playlists"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        contentDescription = "New playlist",
                        onClick = {
                            navigateTo({ a -> TextEditScreen(a, "Playlist name", "") }) { name ->
                                if (!name.isNullOrBlank()) {
                                    scope.launch {
                                        val id = viewModel.createPlaylist(name)
                                        if (id != null) navigateTo({ a -> PlaylistDetailScreen(a, id) })
                                    }
                                }
                            }
                        },
                    ),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onQueueClick = { navigateTo(::QueueScreen) },
        ) {
            LightTextField(
                label = "Search",
                value = query,
                placeholder = "Filter playlists",
                onClick = {
                    navigateTo({ a -> TextEditScreen(a, "Search playlists", query) }) { result ->
                        viewModel.onQueryChange(result)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
            )

            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(playlist) {
                        navigateTo({ a -> PlaylistDetailScreen(a, playlist.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = playlist.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LightText(text = "${playlist.songCount} tracks", variant = LightTextVariant.Fine)
    }
}
