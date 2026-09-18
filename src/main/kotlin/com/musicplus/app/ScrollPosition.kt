package com.musicplus.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

/**
 * Seeds a [LazyListState] from a previously-saved position and keeps writing
 * the live position back via [onPositionChanged] as the person scrolls.
 *
 * Exists because of how this SDK's navigation actually works (confirmed by
 * reading `LightActivity.kt`/`LightScreen.kt` directly): `goBack()` pops the
 * back stack and re-shows the *same* previous screen instance — its
 * `ViewModel` (and `ViewModelStore`) genuinely survive the round trip — but
 * `Content()` for that screen is fully disposed while hidden and recomposed
 * from scratch when it's shown again, so a plain `rememberLazyListState()`
 * called inside `Content()` always resets to the top. Reported live: every
 * list screen scrolled back to the top on `goBack()` instead of restoring
 * where it was. The ViewModel is the one thing here that actually survives,
 * so that's where the position has to live — a plain `var` pair on each
 * screen's ViewModel, not a new cross-screen store.
 */
@Composable
fun rememberPersistedLazyListState(
    initialIndex: Int,
    initialOffset: Int,
    onPositionChanged: (index: Int, offset: Int) -> Unit,
): LazyListState {
    val listState = rememberLazyListState(initialIndex, initialOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> onPositionChanged(index, offset) }
    }
    return listState
}
