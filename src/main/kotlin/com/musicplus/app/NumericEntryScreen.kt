package com.musicplus.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val MAX_DIGITS = 5

/**
 * Digit-only entry, reached wherever a field only ever means a plain positive
 * number (sleep timer minutes, streaming-quality kbps) — built to replace
 * `TextEditScreen` for those two call sites (reported live, 2026-09-18: the
 * sleep timer's "Custom…" row opened `TextEditScreen`, whose embedded
 * keyboard defaults to the full QWERTY layout with only a small "123" key to
 * switch — confirmed via the compiled `light-keyboard` artifact's public API
 * that there's no way to make `EnQwertyLp3KeyboardViewModel` (the only
 * concrete keyboard `TextEditScreen`/`LightTextInputEditor` can construct)
 * start on its number layout instead: its constructor takes no initial-layout
 * parameter, and `Lp3KeyboardViewModel`'s own interface exposes
 * `layoutFlow`/`layoutOptionsFlow` read-only, no setter). Rather than fork
 * the swipe-typing keyboard machinery the way `MaskedTextInputEditor.kt` does
 * for masking, this sidesteps it entirely with a plain digit grid — the only
 * things on screen ARE digits, so there's no "defaults to letters" failure
 * mode possible at all, and no external-artifact limitation to work around.
 *
 * Layout copied from LightOS's own native dialer (checked live on-device,
 * 2026-09-18) minus everything call-specific: same live-display-plus-
 * backspace header row and spacious 3-column digit grid, but no "+" (pause-
 * dial insert), no call button, no add-to-contact pencil — this isn't a
 * phone number, just a plain positive integer, so only entry + [unitSuffix]
 * (e.g. "min", "kbps," rendering small next to the live display, updating
 * with every tap) and SUBMIT survive the trim.
 */
class NumericEntryScreenViewModel(initialValue: String) : LightViewModel<String>() {
    private val _digits = MutableStateFlow(initialValue.filter { it.isDigit() }.take(MAX_DIGITS))
    val digits: StateFlow<String> = _digits

    fun appendDigit(digit: Char) {
        if (_digits.value.length < MAX_DIGITS) _digits.value += digit
    }

    fun backspace() {
        _digits.value = _digits.value.dropLast(1)
    }
}

class NumericEntryScreen(
    activity: SealedLightActivity,
    private val title: String,
    private val initialValue: String,
    private val unitSuffix: String? = null,
) : LightScreen<String, NumericEntryScreenViewModel>(activity) {

    override val viewModelClass = NumericEntryScreenViewModel::class.java
    override fun createViewModel() = NumericEntryScreenViewModel(initialValue)

    @Composable
    override fun Content() {
        val digits by viewModel.digits.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                )
            },
            // Reached mid-flow from another picker screen, same reasoning as
            // SleepTimerPickerScreen's own showMiniPlayer = false.
            showMiniPlayer = false,
            // SUBMIT lives here, not as a plain last item in the content
            // column below — a fixed bottomBar slot is the only way to
            // guarantee it stays reachable regardless of how much vertical
            // space the grid above ends up wanting. Reported live,
            // 2026-09-18 (an earlier version put it as a trailing content
            // row instead, and it landed underneath/behind the grid's own
            // last row on the real device — a tap meant for it registered as
            // an extra "0" instead). Just one action, not a DENY/ACCEPT pair
            // — the top bar's own BACK button already covers "cancel," so a
            // second one down here would be redundant.
            bottomBar = {
                LightBottomBar(
                    items = listOf(LightBarButton.Text(text = "SUBMIT", onClick = { goBack(digits) })),
                )
            },
        ) {
            // Live display + backspace together in their own row, matching
            // the dialer's header (display left, backspace right) rather
            // than burying backspace as just another grid cell.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 1.5f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    LightText(
                        text = digits.ifEmpty { "0" },
                        variant = LightTextVariant.Heading,
                    )
                    if (unitSuffix != null) {
                        LightText(
                            text = " $unitSuffix",
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 0.3f.gridUnitsAsDp()),
                        )
                    }
                }
                if (digits.isNotEmpty()) {
                    LightIcon(
                        icon = LightIcons.DELETE,
                        size = 1.75f,
                        contentDescription = "Backspace",
                        modifier = Modifier.lightClickable(onClick = { viewModel.backspace() }),
                    )
                }
            }

            DigitGrid(
                modifier = Modifier.weight(1f),
                onDigit = { viewModel.appendDigit(it) },
            )
        }
    }
}

/**
 * Fills whatever space [modifier] gives it (the caller passes `weight(1f)`
 * so this shares the screen with the header row above it, rather than
 * sizing itself off each cell's own aspect ratio — an earlier version did
 * that and, combined with Heading-sized digit text, made the 4-row grid
 * taller than the screen, pushing the submit action (then still a trailing
 * row here) out from under the last row's real tap target. Every row/cell
 * below is weight-divided instead, so the whole grid always exactly fills
 * what it's given, on any screen height. 0 sits centered alone on its own
 * row, matching the dialer's layout once `*`/`#` (not needed here) are
 * dropped, rather than the off-center "blank, 0, backspace" arrangement an
 * earlier version used before backspace moved up to the header row.
 */
@Composable
private fun DigitGrid(modifier: Modifier = Modifier, onDigit: (Char) -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (row in listOf("123", "456", "789")) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                for (digit in row) {
                    DigitCell(modifier = Modifier.weight(1f), onClick = { onDigit(digit) }) {
                        LightText(text = digit.toString(), variant = LightTextVariant.Heading)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.weight(1f))
            DigitCell(modifier = Modifier.weight(1f), onClick = { onDigit('0') }) {
                LightText(text = "0", variant = LightTextVariant.Heading)
            }
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DigitCell(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
