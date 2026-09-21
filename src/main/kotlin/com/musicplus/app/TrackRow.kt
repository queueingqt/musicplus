package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import com.musicplus.app.data.DownloadStatus
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Shared shape for every "browsable track row" in the app: tap to play,
 * long-press for actions, the fixed scrollbar-gutter trailing padding every
 * `LightLazyScrollView(Inside)` row needs — and, the actual reason this
 * exists, explicit [showFavorite]/[downloadStatus] a caller now has to
 * decide about rather than silently omit. `TrackResultRow` (SearchScreen)
 * and `FavoriteTrackRow` (FavoritesScreen) both independently dropped one or
 * both glyphs before this existed, next to sibling rows (the old `SongRow`,
 * `AlbumDetailScreen`'s old `TrackRow`, `PlaylistTrackRow`) that always
 * showed both — confirmed live, 2026-09-18 architecture review. Passing
 * `showFavorite = false` explicitly (FavoritesScreen, where every row is
 * definitionally favorited already) now reads as a decision, not a gap.
 *
 * [subtitle] renders inline, trailing the title (not a second line) — matches
 * the original `SongRow`'s exact layout, the one call site that needed an
 * artist name (SongsListScreen spans every album, so titles alone aren't
 * enough to disambiguate). [leading] is a slot for the two genuinely
 * different per-screen additions that aren't part of "a track row" itself:
 * `PlaylistTrackRow`'s conditional reorder icons, and `TrackResultRow`'s
 * album-art thumbnail.
 */
@Composable
fun TrackRow(
    track: Track,
    downloadStatus: DownloadStatus?,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showFavorite: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    // Greyed out when its server is off or cannot be reached and the phone has no copy; a tap says so instead of playing.
    val unavailable = rememberTrackUnavailable(track)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (unavailable) UNAVAILABLE_ALPHA else 1f)
            .lightCombinedClickable(
                onClick = { if (unavailable) NoteModal.show(SERVER_NOT_REACHABLE_NOTE) else onPlay() },
                onLongClick = onOpenActions,
            )
            // end matches the SDK's own scrollbar track width — see
            // ScrollbarGutter.kt's doc (issue #39) for why this is fixed
            // rather than conditional on whether a scrollbar happens to show.
            .padding(top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            LightText(
                text = subtitle,
                variant = LightTextVariant.Fine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Capped: this is measured before the title (the title is the weighted child), so a long one, an artist
                // with a server label ("goosetaf & lōland · NASTY copy (removed)"), would take the whole row and
                // leave the title no width at all.
                modifier = Modifier.widthIn(max = 15f.gridUnitsAsDp()).padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
        if (showFavorite && track.isFavorite) {
            LightIcon(
                icon = LightIcons.STAR,
                size = 1.2f,
                contentDescription = "Favorited",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
        if (downloadStatus != null) {
            LightIcon(
                icon = downloadStatusIcon(downloadStatus),
                size = 1.2f,
                contentDescription = downloadStatusLabel(downloadStatus),
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/** How faint a song that cannot be played right now is drawn. */
const val UNAVAILABLE_ALPHA = 0.4f
