package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

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
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                )
            },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                for (preset in STREAM_QUALITY_PRESETS) {
                    SelectableRow(
                        label = "$preset kbps",
                        selected = current == preset,
                        onClick = { goBack(QualitySelection(preset)) },
                    )
                }
                SelectableRow(
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
                SelectableRow(
                    label = if (isCustom) "Custom ($current kbps)" else "Custom…",
                    selected = isCustom,
                    onClick = {
                        // NumericEntryScreen, not TextEditScreen — see its own
                        // doc for why (this field is kbps-only; no reason to
                        // open on a QWERTY layout). Reported live, 2026-09-18,
                        // against the sleep timer's identical Custom row;
                        // fixed here too since it's the same underlying gap.
                        navigateTo({ a -> NumericEntryScreen(a, title, current?.toString().orEmpty(), unitSuffix = "kbps") }) { result ->
                            result.toIntOrNull()?.takeIf { it > 0 }?.let { goBack(QualitySelection(it)) }
                        }
                    },
                )
            }
        }
    }
}

