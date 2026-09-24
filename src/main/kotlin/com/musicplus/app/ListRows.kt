package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The rows a list screen shows. Every row reserves the scrollbar's width at its end itself: see [ScreenList]. */

/** A tap, and a long-press when [onLongClick] is given. */
@Composable
private fun Modifier.tapAndHold(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick != null) lightCombinedClickable(onClick = onClick, onLongClick = onLongClick) else lightClickable(onClick = onClick)

@Composable
private fun Modifier.listRowPadding(vertical: Float = 1f): Modifier =
    padding(top = vertical.gridUnitsAsDp(), bottom = vertical.gridUnitsAsDp(), start = 1f.gridUnitsAsDp(), end = SCROLLBAR_GUTTER_GRID_UNITS.gridUnitsAsDp())

/** A heading between the sections of one list (Favorites and Search show artists, albums and tracks in one). */
@Composable
fun SectionHeader(title: String) {
    LightText(
        text = title,
        variant = LightTextVariant.Heading,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** One line of text: a favorite or search result with no art. */
@Composable
fun LabelRow(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier.fillMaxWidth().tapAndHold(onClick, onLongClick).listRowPadding(),
    )
}

/** Cover art beside one line of text: an album in Favorites or Search. */
@Composable
fun ArtRow(lightContext: SealedLightContext, label: String, coverArtId: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().tapAndHold(onClick, onLongClick).listRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(lightContext = lightContext, coverArtId = coverArtId, size = 2.5f.gridUnitsAsDp(), modifier = Modifier.padding(end = 1f.gridUnitsAsDp()))
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An album with its art, its name, a second line ([secondLine]: its artist in a list of albums, its track count under the artist who made
 * them all) and a star when it is a favorite. One row for both, where two copies had already drifted.
 */
@Composable
fun AlbumRow(lightContext: SealedLightContext, album: Album, secondLine: String, onClick: () -> Unit, onOpenActions: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions).listRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(lightContext = lightContext, coverArtId = album.coverArtId, size = 2.5f.gridUnitsAsDp(), modifier = Modifier.padding(end = 1f.gridUnitsAsDp()))
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = album.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LightText(text = secondLine, variant = LightTextVariant.Fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (album.isFavorite) FavoritedStar()
    }
}

/** The star that says a row's item is a favorite (there is no other at-a-glance signal in a list). */
@Composable
fun FavoritedStar() {
    LightIcon(icon = LightIcons.STAR, size = 1.2f, contentDescription = "Favorited", modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
}
