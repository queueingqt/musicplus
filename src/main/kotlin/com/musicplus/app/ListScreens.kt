package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.musicplus.app.data.AppAvailability
import com.musicplus.app.data.AvailableSplit
import com.musicplus.app.data.ListAvailability
import com.musicplus.app.data.ListRefresher
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What every list screen shares, so a screen supplies its rows and nothing else: how its state is held while it is on screen
 * ([screenState]), the filter ([ListFilter], [filterButton]), what it does on open ([CachedListViewModel]), its scroll position, and
 * the scrolling list itself ([ScreenList]). The same lines were copied into six list screens and drifted (one renamed the filter's query
 * `_query`; `WhileSubscribed(5_000)` alone appeared 27 times); 11c9c24 made near-identical edits in six of them.
 */

/** How long a screen's state keeps being collected after the screen stops showing it, so a quick step away and back does not restart it. */
private const val SCREEN_STATE_TIMEOUT_MS = 5_000L

/** [this] held as a screen's state: collected while the screen is shown (and for a moment after), starting from [initial]. */
fun <T> Flow<T>.screenState(scope: CoroutineScope, initial: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(SCREEN_STATE_TIMEOUT_MS), initial)

/**
 * A screen's client-side "search within this list": one [query] narrows any number of lists (Favorites has three). It only narrows what is
 * already cached and never asks a server; SearchScreen's own search is a different thing.
 */
class ListFilter(private val scope: CoroutineScope) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun set(query: String) {
        _query.value = query
    }

    /** [source] narrowed by the current query: all of it while the query is blank, else the rows [matches] accepts. */
    fun <T> narrow(source: Flow<List<T>>, matches: (T, String) -> Boolean): StateFlow<List<T>> =
        filteredBy(source, _query, matches).screenState(scope, emptyList())

    /**
     * [narrow], then cut by whether each row can be played right now ([split] is one of [ListAvailability]'s functions): the rows that
     * can, then the rows that cannot. Follows the servers coming and going and "Downloaded only", so a row moves without the screen being reopened.
     */
    fun <T> narrowAndSplit(
        source: Flow<List<T>>,
        matches: (T, String) -> Boolean,
        split: ListAvailability.(List<T>) -> AvailableSplit<T>,
    ): StateFlow<AvailableSplit<T>> =
        combine(filteredBy(source, _query, matches), AppAvailability.now.value) { rows, availability -> availability.split(rows) }
            .screenState(scope, AvailableSplit.empty())
}

/** Whether [this] contains [query], ignoring case: what every list's filter means by "matches". */
fun String?.containsIgnoringCase(query: String): Boolean = this?.contains(query, ignoreCase = true) == true

/** A screen's ViewModel, which is the one thing that survives a navigate-away and `goBack()`: it holds the scroll position (see [ScrollPosition]). */
abstract class ListScreenViewModel : LightViewModel<Unit>() {
    val scrollPosition = ScrollPosition()
}

/**
 * A list page that opens straight from the process-lifetime cache and, on open, asks [ListRefresher] to re-check the server in the
 * background; any additions or deletions arrive through the cache itself, with no spinner. See ListRefresher's doc.
 */
abstract class CachedListViewModel(
    private val listRefresher: ListRefresher,
    private val target: ListRefresher.Target,
) : ListScreenViewModel() {
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        listRefresher.refreshOnOpen(target)
    }
}

/**
 * The screen's search icon: opens the text editor with the current [filter] and hands back the new one.
 * [noun] is what is searched ("albums").
 */
fun SimpleLightScreen<*>.filterButton(noun: String, filter: String, onFilter: (String) -> Unit): LightBarButton =
    LightBarButton.LightIcon(
        icon = LightIcons.SEARCH,
        onClick = { navigateTo({ a -> TextEditScreen(a, "Search $noun", filter) }) { result -> onFilter(result) } },
        contentDescription = "Search $noun",
    )

/**
 * The scrolling list every screen uses: the scrollbar overlays the rows (Inside) rather than reserving a gutter, because the Outside
 * gutter's width is not known until after the first layout pass, so a width-dependent row (a truncated title, a trailing star) briefly
 * rendered too wide and then visibly jumped narrower once it appeared (issue #39, reported live). Rows reserve the same width
 * themselves, unconditionally ([SCROLLBAR_GUTTER_GRID_UNITS]). Its scroll position survives leaving the screen and coming back.
 */
@Composable
fun ScreenList(
    position: ScrollPosition,
    uniformItemHeightGridUnits: Float = 3f,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberPersistedLazyListState(position)
    LightLazyScrollView(
        modifier = Modifier.fillMaxWidth(),
        scrollBarPosition = LightScrollBarPosition.Inside,
        listState = listState,
        uniformItemHeightGridUnits = uniformItemHeightGridUnits,
        content = content,
    )
}
