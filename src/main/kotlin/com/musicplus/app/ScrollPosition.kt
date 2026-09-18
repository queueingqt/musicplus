package com.musicplus.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

/**
 * A list screen's scroll position, held as one mutable property on that
 * screen's ViewModel instead of a separate index/offset var pair.
 */
class ScrollPosition(var index: Int = 0, var offset: Int = 0)

/**
 * Seeds a [LazyListState] from a previously-saved [position] and keeps
 * writing the live position back into it as the person scrolls.
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
 * so that's where the position has to live — one [ScrollPosition] per screen's
 * ViewModel, not a new cross-screen store.
 */
@Composable
fun rememberPersistedLazyListState(position: ScrollPosition): LazyListState {
    val listState = rememberLazyListState(position.index, position.offset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                position.index = index
                position.offset = offset
            }
    }
    return listState
}
