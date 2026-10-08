package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What happens when an [ActionMenuItem] is tapped.
 */
sealed interface ActionMenuSelection {
    /**
     * Runs [action] and updates just that one row with whatever it returns —
     * the menu itself never closes on its own from this. Reported live:
     * tapping "Add to favorites" used to call `goBack()` the instant the
     * write finished, closing the whole menu instead of just flipping to
     * "Remove from favorites" in place. [action] returns the row's new
     * icon/label (e.g. the flipped favorite state) to replace itself with, or
     * `null` if the row no longer applies at all (e.g. "Remove from
     * playlist" — its own subject is now gone), in which case just that row
     * is dropped rather than the whole menu closing. The person closes this
     * screen themselves, same as any other screen.
     */
    class Perform(val action: suspend () -> ActionMenuItem?) : ActionMenuSelection

    /** Leave this menu and open another screen, instead of returning to the row's screen. */
    class Navigate(val open: () -> Unit) : ActionMenuSelection
}

data class ActionMenuItem(
    val icon: LightIconConfiguration,
    val label: String,
    val onSelect: ActionMenuSelection,
    /**
     * Optional live source for this row, independent of taps — reported
     * live: a download in progress when the menu opened just sat there
     * still saying "Downloading" long after it had actually finished,
     * because (unlike a favorite toggle, which completes instantly) a
     * download is a real multi-second background process the person might
     * watch this same open menu through. Most rows have nothing that
     * changes without a tap and leave this null; [ActionsMenuScreen]
     * collects it for the ones that do and replaces the row every time it
     * emits, the same as it would after a [ActionMenuSelection.Perform].
     */
    val liveUpdates: Flow<ActionMenuItem>? = null,
    /**
     * Stable identity for matching a live update back to its row —
     * deliberately separate from [label], which is exactly what a live
     * update (or a tap) usually changes. Defaults to [label] for a row whose
     * label genuinely never changes over its lifetime in one menu; a row
     * built by a self-updating helper (see [favoriteActionItem]) passes its
     * own fixed key instead.
     */
    val key: String = label,
)

/**
 * Builds a self-updating "Add/Remove favorites" row shared by every call
 * site: each tap flips [isFavorite], calls [toggle] with the new value, and
 * rebuilds itself reflecting that — the same shape as the old value, not a
 * one-shot action, so this is the one place that needs to know how.
 */
fun favoriteActionItem(isFavorite: Boolean, toggle: suspend (Boolean) -> Unit): ActionMenuItem =
    ActionMenuItem(
        key = "favorite",
        icon = if (isFavorite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
        label = if (isFavorite) "Remove from favorites" else "Add to favorites",
        onSelect = ActionMenuSelection.Perform {
            val newValue = !isFavorite
            toggle(newValue)
            favoriteActionItem(newValue, toggle)
        },
    )

/**
 * Builds a row that opens a [ConfirmModal] on tap instead of acting
 * immediately — [onConfirm] only runs if the person confirms. Always returns
 * itself: unlike [favoriteActionItem]'s "always some new state," this row
 * still applies whether the dialog it opens gets confirmed or cancelled, so
 * there's no `null` case. That's the one shape easy to get backwards by hand
 * — a plain `null` return here drops the row from the menu the instant it's
 * tapped, before the dialog even shows. Copied wrong twice (PlaylistListScreen,
 * PlaylistDetailScreen, ServerSettingsScreen all hand-rolled the identical
 * `lateinit var` idiom, and two of the three shipped with the `null` bug) —
 * this is that idiom owned once instead of hand-copied at every call site,
 * matching [favoriteActionItem]/`addToQueueActionItem`'s existing pattern.
 */
fun confirmActionItem(
    icon: LightIconConfiguration,
    label: String,
    confirmTitle: String,
    confirmMessage: String,
    confirmContentDescription: String = label,
    onConfirm: () -> Unit,
): ActionMenuItem {
    lateinit var item: ActionMenuItem
    item = ActionMenuItem(
        icon = icon,
        label = label,
        onSelect = ActionMenuSelection.Perform {
            confirm(confirmTitle, confirmMessage, confirmContentDescription, onConfirm)
            item
        },
    )
    return item
}

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
 * was long-pressed (current favorite state, current download status, and so
 * on) — not a live observation of those values. [Content] keeps its own
 * mutable copy afterward, updated in place from whatever each
 * [ActionMenuSelection.Perform] returns, so a snapshot going stale after the
 * first tap isn't a problem the way it would be for something read once and
 * never revisited.
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
 *
 * Second implementation note (issue #20, still relevant even though
 * [ActionMenuSelection.Perform] no longer auto-closes): the back button, and
 * any [ActionMenuSelection.Navigate] row, are only ever acted on once per
 * screen instance, via the `closing` latch in [Content]. `LightActivity`'s
 * back stack (`currentScreen`/`backStack` in `LightActivity.kt`) is
 * Activity-global state, not scoped to this screen, so a *second* `goBack()`
 * call doesn't harmlessly no-op just because this screen already popped
 * itself once — it pops whatever screen is now on top, i.e. the one this
 * menu was opened from. [ActionRow] uses `lightClickable`, which by design
 * shows no press indication (see `LightClickable.kt`), so a person gets no
 * visual confirmation their tap landed; an accidental second tap on the back
 * icon (or a [Navigate] row) landing before this screen is actually torn
 * down would, without this guard, silently close the caller's screen too.
 * `busy` is the equivalent guard for [Perform] rows — blocks a second tap
 * (any row, not just the same one) while one is still in flight, so two
 * favorite toggles fired in quick succession can't race each other.
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
        // All of the menu's state is MenuState's (see its doc and the class doc above): this only draws it and routes taps to it.
        val state = remember { MenuState(items) }
        val visibleItems by state.rows.collectAsState()

        LaunchedEffect(Unit) { state.followLiveUpdates() }

        fun close() {
            if (state.beginClose()) goBack()
        }

        fun handleSelection(item: ActionMenuItem) {
            when (val selection = item.onSelect) {
                is ActionMenuSelection.Perform -> scope.launch { state.perform(item.key, selection) }
                is ActionMenuSelection.Navigate -> state.navigate(selection, goBack = { goBack() })
            }
        }

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { close() },
                    ),
                    center = LightTopBarCenter.TwoLineDetail(line1 = "Actions", line2 = subtitle),
                )
            },
        ) {
            // Inside, not the default Outside — see ScrollbarGutter.kt's doc
            // (issue #39): ActionRow's maxLines=1/Ellipsis label is
            // width-dependent, so on Outside it briefly truncated less (wider,
            // closer to the true edge) on the first frame, then visibly
            // snapped narrower once the scrollbar's real gutter was reserved.
            LightLazyScrollView(
                modifier = Modifier.fillMaxWidth(),
                scrollBarPosition = LightScrollBarPosition.Inside,
                uniformItemHeightGridUnits = 3f,
            ) {
                items(visibleItems, key = { it.key }) { item ->
                    ActionRow(item) { handleSelection(item) }
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
            // end matches the SDK's own scrollbar track width — see the
            // LightLazyScrollView call site above for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = item.icon,
            size = 1.5f,
            // Decorative, not contentDescription = item.label: this Row is one
            // merged accessibility node (ActionRow's own lightClickable merges
            // its descendants' semantics — verified against the actual
            // androidx.compose.foundation build this app depends on:
            // AbstractClickableNode.shouldMergeDescendantSemantics() unconditionally
            // returns true). The LightText right below already carries this exact
            // same string as real text in that same merged node, so labelling the
            // icon too added nothing a screen reader doesn't already get from the
            // text — every row in every action menu app-wide (favorite/download/
            // rename/delete/edit order/add to queue) shares this component. Issue #36.
            contentDescription = null,
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
