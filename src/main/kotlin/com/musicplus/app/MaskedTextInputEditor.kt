package com.musicplus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.*
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightBottomBarItem
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.flow.StateFlow

// Local fork of the SDK's `LightTextInputEditor`
// (sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/LightTextInputEditor.kt), changed
// ONLY so the rendered text is masked instead of shown in plain text — see the
// `BasicText(text = ...)` call in the second overload below, the sole behavioral diff.
//
// Why this exists (tracked issue #2): the real
// `LightTextInputEditor` renders `state.text` directly via `BasicText` with no
// `visualTransformation`-style hook, and its `KeyboardOptions` type comes from the
// external `light-keyboard` Maven artifact (`com.github.lightphone:light-keyboard`),
// which isn't source-available to patch. Checked upstream before writing this (2026-09-17):
// the version pinned in gradle/libs.versions.toml (v0.0.19) is also the *latest* tag on
// github.com/lightphone/light-keyboard, and that version's `KeyboardOptions` data class
// has no masking/password/secure-text field at all — so there's no newer-artifact fix to
// pick up (option 1 in the issue), which leaves this local duplicate (option 2) as the
// only way to fix it without waiting on upstream.
//
// Two symbols the original uses are `internal` to the `:sdk:ui` module and can't be
// imported from this tool module, so they're reimplemented below verbatim — neither is
// related to masking, both are just needed to compile:
//  - `TextStyle`/`TextUnit`.scaledForScreenHeight() (sdk/ui/LightText.kt, `internal fun`)
//  - `TextInputKeyboardCallback` (sdk/ui/keyboard/TextInputKeyboardCallback.kt, `internal class`)
//
// One more, discovered only by actually building this against light-sdk (the `:tool`
// module's Gradle plugin statically rejects it, see `LightSdkPlugin.BLOCKED_IMPORTS` /
// `BLOCKED_CODE_PATTERNS`): the original also reads `LocalContext.current` to call
// `LightHapticFeedback.click(context)` on every keypress. `LocalContext` — and any other
// way to obtain a raw `android.content.Context` — is deliberately unreachable from tool
// code (`SealedLightContext.androidContext` is `internal` to `:sdk:client` for the same
// reason). That's a real sandboxing boundary, not a workaround-able oversight, so this
// fork's `onHaptic` is a no-op: typing in the masked password field has no per-key
// haptic buzz, unlike every other field. Nothing else about behavior changes.
//
// Everything else — layout, top bar, cursor handling, bottom bar / keyboard wiring — is
// copied as closely as possible to the original so this is easy to diff against it and
// delete once upstream ships real masking support.
//
// Only wired into SettingsScreen.kt's password field; every other field keeps using the
// real `TextEditScreen` / `LightTextInputEditor`.

private const val MASKED_INPUT_UNDERLINE_THICKNESS_PX = 3f
private const val MASKED_INPUT_UNDERLINE_GAP_GRID_UNITS = 0.5f

@Composable
fun MaskedTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    submitIcon: LightIconConfiguration? = null,
    leftBottomBarItem: LightBottomBarItem? = null,
    rightBottomBarItem: LightBottomBarItem? = null,
    showBackButton: Boolean = true,
    singleLine: Boolean = false,
    initialCaps: Boolean = false,
    editorKey: Any = remember { Any() },
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    // No haptic-on-keypress here — see file header: raw Context (needed by
    // LightHapticFeedback.click()) is not reachable from tool-module code at all.
    val keyboardCallback = remember(state, singleLine) {
        MaskedTextInputKeyboardCallback(
            state = state,
            singleLine = singleLine,
            onReturn = { currentOnSubmit(state.text) },
            onHaptic = {},
        )
    }

    val keyboardViewModel: Lp3KeyboardViewModel<*> = viewModel<EnQwertyLp3KeyboardViewModel<*>>(
        key = "MaskedTextInputEditor-$editorKey",
        factory = maskedFactory(keyboardCallback, keyboardOptionsFlow, initialCaps),
    )

    MaskedTextInputEditor(
        title,
        state,
        onSubmit,
        onBack,
        keyboardViewModel,
        modifier,
        submitLabel,
        submitIcon,
        leftBottomBarItem,
        rightBottomBarItem,
        showBackButton,
        singleLine,
    )
}

/**
 * Full-screen text entry matching LightOS `DisplayWithKeyboardPortrait`, identical to
 * the SDK's `LightTextInputEditor` except the on-screen text is masked (see the
 * `BasicText` call below) — see the file header for why this duplicate exists.
 */
@Composable
fun MaskedTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    viewModel: Lp3KeyboardViewModel<*>,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    submitIcon: LightIconConfiguration? = null,
    leftBottomBarItem: LightBottomBarItem? = null,
    rightBottomBarItem: LightBottomBarItem? = null,
    showBackButton: Boolean = true,
    singleLine: Boolean = false,
) {
    val colors = LightThemeTokens.colors
    val inputStyle = maskedInputTextStyle()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Surface {
        Column(modifier = modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = if (showBackButton) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = onBack,
                    )
                } else {
                    null
                },
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp())
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            textLayout?.let { layout ->
                                state.edit {
                                    selection =
                                        TextRange(layout.getOffsetForPosition(down.position))
                                }
                            }
                            drag(down.id) { change ->
                                textLayout?.let { layout ->
                                    state.edit {
                                        selection =
                                            TextRange(layout.getOffsetForPosition(change.position))
                                    }
                                }
                                change.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        // Masked — this is the entire point of this fork. The original
                        // (`LightTextInputEditor`) renders `state.text.toString()` here
                        // in plain text; everything else in this file is unchanged.
                        // Length-preserving so tap/drag cursor placement (below, via
                        // `textLayout.getOffsetForPosition`) still maps 1:1 onto real
                        // character offsets in `state.text`.
                        text = "•".repeat(state.text.length),
                        style = inputStyle,
                        onTextLayout = { textLayout = it },
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        softWrap = !singleLine,
                        overflow = if (singleLine) TextOverflow.StartEllipsis else TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(
                        modifier = Modifier.height(
                            MASKED_INPUT_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp(),
                        ),
                    )
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MASKED_INPUT_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                            .background(colors.content),
                    )
                }
                textLayout?.let { layout ->
                    val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                    val rect = layout.getCursorRect(cursorPos)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                            .width(2.dp)
                            .height(with(LocalDensity.current) { rect.height.toDp() })
                            .background(colors.content),
                    )
                }
            }

            LightEmbeddedLp3Keyboard(
                viewModel = viewModel,
                additionalBottomHeight = 5f.gridUnitsAsDp(),
                bottomBar = {
                    val submitItem: LightBottomBarItem = when (submitIcon) {
                        null -> LightBarButton.Text(
                            text = submitLabel,
                            onClick = { onSubmit(state.text) },
                        )
                        else -> LightBarButton.LightIcon(
                            icon = submitIcon,
                            onClick = { onSubmit(state.text) },
                            contentDescription = submitLabel,
                        )
                    }
                    LightBottomBar(
                        items = if (leftBottomBarItem == null && rightBottomBarItem == null) {
                            listOf(submitItem)
                        } else {
                            listOf(leftBottomBarItem, submitItem, rightBottomBarItem)
                        },
                    )
                }
            )
        }
    }
}

private fun maskedFactory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    initialCaps: Boolean,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = {
                    val showCloseButton = !it.isRootLayout
                    LayoutOptions(showCloseButton)
                },
            ).apply {
                if (initialCaps) setCapsMode(true)
            } as T
        }

    }

@Composable
private fun maskedInputTextStyle(): TextStyle {
    val colors = LightThemeTokens.colors
    val t = LightThemeTokens.typography
    return t.heading
        .copy(
            color = colors.content,
        )
        .scaledForScreenHeightLocal()
}

// Reimplementation of the SDK's `internal fun TextStyle.scaledForScreenHeight()`
// (sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/LightText.kt) — copied verbatim.
// Needed only because the original is `internal` to `:sdk:ui` and this file lives in
// the tool module; unrelated to masking.
@Composable
private fun TextStyle.scaledForScreenHeightLocal(): TextStyle {
    val fontSize = fontSize.scaledForScreenHeightLocal()
    val lineHeight = lineHeight.scaledForScreenHeightLocal()
    val letterSpacing = letterSpacing.scaledForScreenHeightLocal()
    return copy(
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
    )
}

@Composable
private fun TextUnit.scaledForScreenHeightLocal(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}

// Reimplementation of the SDK's `internal class TextInputKeyboardCallback`
// (sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/keyboard/TextInputKeyboardCallback.kt)
// — copied verbatim (keystroke-to-`TextFieldState` plumbing only; nothing here is
// masking-related). Needed only because the original is `internal` to `:sdk:ui`.
private class MaskedTextInputKeyboardCallback(
    private val state: TextFieldState,
    private val singleLine: Boolean = false,
    private val onReturn: () -> Unit = {},
    private val onHaptic: () -> Unit = {},
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) {
        onHaptic()
    }

    override fun onSpecialKeyPressed(key: SpecialKey) {
        onHaptic()
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) {
        insertCodePoint(code)
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(surrogateAwareDeleteCountLocal(before, 1))
            }
            SpecialKey.Return -> if (singleLine) onReturn() else insertAtCursor("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            deleteBeforeCursor(deleteWordCountLocal(before))
        }
    }

    override fun onKeyRepeated(code: Int) {
        insertCodePoint(code)
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    private fun insertCodePoint(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    override fun onSubmitWord(word: CharSequence) {
        insertAtCursor(word.toString())
    }

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

private fun surrogateAwareDeleteCountLocal(value: CharSequence, defaultCount: Int): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else defaultCount
}

private fun deleteWordCountLocal(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}
