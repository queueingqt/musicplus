package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A tappable row in a single-choice list, with a leading checkmark when
 * [selected] — QualityPickerScreen's `QualityRow` and
 * SleepTimerPickerScreen's `SleepTimerRow` were byte-for-byte identical
 * copies of this exact composable, each one's own comment admitting it —
 * confirmed live, 2026-09-18 architecture review.
 */
@Composable
fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            LightIcon(
                icon = LightIcons.SELECT_ON,
                size = 1.5f,
                contentDescription = "Selected",
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}
