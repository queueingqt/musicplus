package com.lightwave.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.PlaybackRepositoryHolder
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeScreenViewModel(
    private val graph: AppGraph.Graph,
) : LightViewModel<Unit>() {
    val isConfigured: StateFlow<Boolean> = graph.serverConfigRepository.serverConfig
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Non-creating peek — see PlaybackRepositoryHolder: Home shouldn't itself spend
    // the app's one detached-audio handle just by being shown.
    val nowPlayingTitle: StateFlow<String?> =
        (PlaybackRepositoryHolder.peek()?.state?.map { it.currentTrack?.title }
            ?: MutableStateFlow(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch {
            graph.libraryRepository.refreshAlbumList()
            graph.libraryRepository.refreshArtists()
        }
    }
}

@InitialScreen
class HomeScreen(activity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(activity) {
    override val viewModelClass = HomeScreenViewModel::class.java
    override fun createViewModel() = HomeScreenViewModel(AppGraph.from(lightContext))

    @Composable
    override fun Content() {
        val isConfigured by viewModel.isConfigured.collectAsState()
        val nowPlaying by viewModel.nowPlayingTitle.collectAsState()

        Column {
            LightTopBar(center = LightTopBarCenter.Text("Lightwave"))
            LightScrollView(modifier = Modifier.fillMaxWidth()) {
                if (!isConfigured) {
                    MenuRow("Set up your server") { navigateTo(::SettingsScreen) }
                }
                if (nowPlaying != null) {
                    MenuRow("Now playing: $nowPlaying") { navigateTo(::PlayerScreen) }
                }
                MenuRow("Albums") { navigateTo(::AlbumListScreen) }
                MenuRow("Artists") { navigateTo(::ArtistListScreen) }
                MenuRow("Search") { navigateTo(::SearchScreen) }
                MenuRow("Favorites") { navigateTo(::FavoritesScreen) }
                MenuRow("Settings") { navigateTo(::SettingsScreen) }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
    )
}
