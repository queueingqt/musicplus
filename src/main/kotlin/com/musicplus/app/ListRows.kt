package com.musicplus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import com.musicplus.app.data.AvailableSplit
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

/**
 * The rows of [split]: the ones that can be played now, then a line saying the server is not reachable (when some are and some are not,
 * see [AvailableSplit.hasLine]) and the ones that cannot, each row told whether it is one of those so it can draw itself faint (see [UNAVAILABLE_ALPHA]). [keyPrefix] keeps the line's key
 * apart when one list holds several of these (Favorites).
 */
fun <T> LazyListScope.availableItems(
    split: AvailableSplit<T>,
    key: (T) -> Any,
    keyPrefix: String = "",
    row: @Composable (item: T, unavailable: Boolean) -> Unit,
) {
    items(split.playable, key = key) { row(it, false) }
    if (split.hasLine) item(key = "${keyPrefix}unavailable-line") { UnavailableLine() }
    items(split.unavailable, key = key) { row(it, true) }
}

/** What separates the rows that can be played from the ones whose server cannot be reached. Reads like [EmptyListNote]: plain, no banner. */
@Composable
private fun UnavailableLine() {
    LightText(
        text = SERVER_NOT_REACHABLE_NOTE,
        variant = LightTextVariant.Fine,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** Draws a row faint when [unavailable]: its server cannot be reached and the phone has no audio for it. */
@Composable
fun Modifier.fadedWhen(unavailable: Boolean): Modifier = alpha(if (unavailable) UNAVAILABLE_ALPHA else 1f)

/** One line of text: a favorite or search result with no art. */
@Composable
fun LabelRow(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, unavailable: Boolean = false) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier.fillMaxWidth().fadedWhen(unavailable).tapAndHold(onClick, onLongClick).listRowPadding(),
    )
}

/** Cover art beside one line of text: an album in Favorites or Search. */
@Composable
fun ArtRow(
    lightContext: SealedLightContext,
    label: String,
    coverArtId: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    unavailable: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().fadedWhen(unavailable).tapAndHold(onClick, onLongClick).listRowPadding(),
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
fun AlbumRow(
    lightContext: SealedLightContext,
    album: Album,
    secondLine: String,
    onClick: () -> Unit,
    onOpenActions: () -> Unit,
    unavailable: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().fadedWhen(unavailable).lightCombinedClickable(onClick = onClick, onLongClick = onOpenActions).listRowPadding(),
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
