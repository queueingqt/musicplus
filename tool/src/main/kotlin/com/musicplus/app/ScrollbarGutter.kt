package com.musicplus.app

/**
 * Matches the SDK's own `SCROLLBAR_WIDTH_UNITS` (`sdk/ui/.../LightScrollView.kt`)
 * — `private` there, so duplicated here rather than referenced directly.
 *
 * Every list row rendered inside a `LightLazyScrollView(scrollBarPosition =
 * LightScrollBarPosition.Inside)` needs to reserve exactly this much trailing
 * space *itself*, unconditionally, rather than relying on the SDK's own
 * dynamic `Outside`-mode gutter. Root cause (issue #39): that gutter's width
 * comes from a `derivedStateOf` over `listState.layoutInfo`, which reads as
 * zero until the LazyColumn's first real layout pass — so on `Outside`, any
 * row content flush against the trailing edge (an icon after a `weight(1f)`
 * title, say) briefly renders at the wrong, full-container width for one
 * frame, then visibly jumps left once the real gutter is reserved. Reported
 * live on QueueScreen; `Inside` plus each row unconditionally padding for
 * this constant (so nothing depends on `Outside`'s racy derived state) is
 * the fix already established for AlbumListScreen/AlbumDetailScreen/
 * ArtistDetailScreen/SongsListScreen/PlaylistDetailScreen/QueueScreen.
 *
 * A single named constant instead of every row hand-writing the literal
 * `2f` — six call sites already did, independently, before this existed;
 * a shared name means they can't quietly drift from the SDK's own value or
 * from each other.
 */
const val SCROLLBAR_GUTTER_GRID_UNITS = 2f
