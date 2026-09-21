package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.musicplus.app.data.PlaylistHomes
import com.musicplus.app.data.ServerScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class SaveToScreenViewModel : LightViewModel<String>()

/**
 * Where a new playlist that starts from nothing is saved: every server that is on and can keep playlists, and Phone Only.
 * Nothing is preselected. Result-based like [TextEditScreen]: picking a row returns its id (a server id, or
 * [ServerScope.PHONE]); backing out returns nothing, so nothing gets created.
 */
class SaveToScreen(
    activity: SealedLightActivity,
    private val choices: List<PlaylistHomes.Home>,
) : LightScreen<String, SaveToScreenViewModel>(activity) {

    override val viewModelClass = SaveToScreenViewModel::class.java
    override fun createViewModel() = SaveToScreenViewModel()

    @Composable
    override fun Content() {
        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Save to"),
                )
            },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth()) {
                for (home in choices) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { goBack(home.id) }
                            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        LightText(text = home.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (home.id == ServerScope.PHONE) {
                            LightText(text = "Stays on this phone", variant = LightTextVariant.Fine)
                        }
                    }
                }
            }
        }
    }
}
