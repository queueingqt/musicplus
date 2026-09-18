package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch

/**
 * What happens when an [ActionMenuItem] is tapped.
 *
 * Two cases, not one plain `suspend () -> Unit`, because they need different
 * sequencing around [ActionsMenuScreen]'s own `goBack()` — see that class's
 * doc comment for why the ordering isn't just style.
 */
sealed interface ActionMenuSelection {
    /** Run [action] to completion, then return to the screen that opened this menu. */
    class Perform(val action: suspend () -> Unit) : ActionMenuSelection

    /** Leave this menu and open another screen, instead of returning to the row's screen. */
    class Navigate(val open: () -> Unit) : ActionMenuSelection
}

data class ActionMenuItem(
    val icon: LightIconConfiguration,
    val label: String,
    val onSelect: ActionMenuSelection,
)

class ActionsMenuScreenViewModel : LightViewModel<Unit>()

/**
 * Reusable full-screen "long-press this row" action menu (issue #16) — the
 * replacement for the per-row icon strips that used to carry 2-4 icons each
 * (favorite, add-to-queue, add-to-playlist, download, ...). One screen class
 * serves every long-press site in the app (album/search/favorites/playlist
 * track rows, and the album header) rather than a separate
 * `TrackActionsScreen`/`AlbumActionsScreen` pair: [items] arrives already
 * fully resolved — icon, label, and what to do — so this screen itself has
 * no track/album-specific knowledge at all. A second, item-type-specific
 * class would just be a copy of this same plain list-of-rows layout.
 *
 * [subtitle] names the item the actions apply to (e.g. the track title),
 * shown under a fixed "Actions" heading in the top bar — once the
 * originating row has scrolled out from behind this screen there's
 * otherwise no way to tell what the menu is even for.
 *
 * [items] is a plain snapshot resolved by the caller at the moment the row
 * was long-pressed (current favorite state, current download status, and
 * so on) — not a live observation of those values. That's deliberate: every
 * [ActionMenuSelection.Perform] item calls `goBack()` as soon as its action
 * finishes, so this screen is never on-screen long enough for a stale
 * snapshot to matter, and it keeps this screen fully decoupled from any
 * particular caller's ViewModel/Flows.
 *
 * Implementation note for future call sites: [ActionMenuSelection.Perform]'s
 * `action` runs on *this* screen's own `rememberCoroutineScope()`, not
 * whatever scope the calling row's screen used when building the closure.
 * That's required, not a style choice: a `CoroutineScope` from
 * `rememberCoroutineScope()` on the calling screen is cancelled the instant
 * that screen stops being the one actually composed on screen — which
 * happens as soon as this menu opens on top of it, well before the person
 * has tapped anything in here. Launching `action` on that captured scope
 * would silently no-op. A `Perform.action` that instead calls a ViewModel
 * method backed by `viewModelScope` is fine regardless of which scope
 * "runs" it, since `viewModelScope` outlives composition (cleared only when
 * that screen is itself popped) — but an `action` should never wrap its own
 * body in `scope.launch { }` against a scope it captured from elsewhere.
 */
class ActionsMenuScreen(
    activity: SealedLightActivity,
    private val subtitle: String,
    private val items: List<ActionMenuItem>,
) : LightScreen<Unit, ActionsMenuScreenViewModel>(activity) {

    override val viewModelClass = ActionsMenuScreenViewModel::class.java
    override fun createViewModel() = ActionsMenuScreenViewModel()

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()

        LightwaveScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.TwoLineDetail(line1 = "Actions", line2 = subtitle),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            LightLazyScrollView(modifier = Modifier.fillMaxWidth(), uniformItemHeightGridUnits = 3f) {
                items(items, key = { it.label }) { item ->
                    ActionRow(item) {
                        when (val selection = item.onSelect) {
                            is ActionMenuSelection.Perform -> scope.launch {
                                selection.action()
                                goBack()
                            }
                            is ActionMenuSelection.Navigate -> {
                                // goBack() *before* opening the next screen so it lands directly
                                // on top of the row's own screen instead of on top of this menu —
                                // otherwise its own goBack() would only return here, leaving one
                                // extra screen for the person to dismiss afterward.
                                goBack()
                                selection.open()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(item: ActionMenuItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = item.icon,
            size = 1.5f,
            contentDescription = item.label,
            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = item.label,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
