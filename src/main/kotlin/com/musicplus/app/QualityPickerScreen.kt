package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A non-null wrapper around the actually-nullable result — [LightScreen]'s
 * `goBack(result: ResultType?)` already uses `null` to mean "cancelled, no
 * result" (see TextEditScreen's doc), and this picker's own legitimate
 * "Original" answer is *also* a null `maxBitRateKbps`. Without this wrapper
 * those two nulls would collide: picking Original would look identical to
 * backing out without picking anything.
 */
data class QualitySelection(val maxBitRateKbps: Int?)

/**
 * One setting's value picker — [title] and [current] are just this call's
 * inputs, not observed state, since the caller ([StreamingQualityScreen])
 * owns the real StateFlow and applies whatever comes back through its own
 * `navigateTo(...) { }` result callback.
 */
class QualityPickerScreenViewModel : LightViewModel<QualitySelection>()

class QualityPickerScreen(
    activity: SealedLightActivity,
    private val title: String,
    private val current: Int?,
) : LightScreen<QualitySelection, QualityPickerScreenViewModel>(activity) {

    override val viewModelClass = QualityPickerScreenViewModel::class.java

    override fun createViewModel() = QualityPickerScreenViewModel()

    @Composable
    override fun Content() {
        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                for (preset in STREAM_QUALITY_PRESETS) {
                    QualityRow(
                        label = "$preset kbps",
                        selected = current == preset,
                        onClick = { goBack(QualitySelection(preset)) },
                    )
                }
                QualityRow(
                    label = "Original",
                    selected = current == null,
                    onClick = { goBack(QualitySelection(null)) },
                )
                // A value that isn't null and isn't one of the presets can only
                // have gotten there through this same row, so it's the current
                // custom value, not a placeholder — labeled with the number
                // rather than a generic "Custom" so it reads as "this is what's
                // set," not "tap to configure."
                val isCustom = current != null && current !in STREAM_QUALITY_PRESETS
                QualityRow(
                    label = if (isCustom) "Custom ($current kbps)" else "Custom…",
                    selected = isCustom,
                    onClick = {
                        navigateTo({ a -> TextEditScreen(a, "$title (kbps)", current?.toString().orEmpty()) }) { result ->
                            result.toIntOrNull()?.takeIf { it > 0 }?.let { goBack(QualitySelection(it)) }
                        }
                    },
                )
            }
        }
    }
}

// Leading state indicator, not trailing — same "which one of these is
// current" pattern as ServerSettingsScreen's ServerRow (LightIcons.SELECT_ON
// before the label), the established convention in this app.
@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
