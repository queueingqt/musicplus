package com.lightwave.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

class TextEditScreenViewModel(initialValue: String) : LightViewModel<String>() {
    val fieldState = TextFieldState(initialValue)
    val keyboardOptions = MutableStateFlow<KeyboardOptions>(defaultKeyboardOptions())
}

/**
 * Reusable full-screen text editor — the real LightOS pattern (`LightTextInputEditor`
 * + its own embedded LP3 keyboard), reached by tapping a read-only `LightTextField`.
 * Replaces the earlier `BasicTextField` stand-in, which triggered the system Android
 * IME instead: that made typed text render black-on-black (LightOS's dark theme) and,
 * worse, apparently broke navigation on real hardware (Light Phone III has no gesture
 * navigation at all — see feedback_light_phone_navigation memory note) — both found
 * only by testing on a physical device.
 *
 * Result-based: submitting calls `goBack(text)`; the back button calls plain
 * `goBack()` (null), which `LightActivity.deliverResult()` treats as "cancelled" and
 * never invokes the caller's `resultCallback` — so `navigateTo({ a -> TextEditScreen(...) }) { result -> ... }`
 * only updates state on an actual submit.
 *
 * Not used for the password field — `LightTextInputEditor` has no masking option
 * (there's no such parameter on it, and `KeyboardOptions` comes from the external
 * `light-keyboard` artifact, which isn't source-available to check further), so
 * Settings keeps a `BasicTextField` + `PasswordVisualTransformation` for that one field.
 */
class TextEditScreen(
    activity: SealedLightActivity,
    private val title: String,
    private val initialValue: String,
    private val singleLine: Boolean = true,
) : LightScreen<String, TextEditScreenViewModel>(activity) {

    override val viewModelClass = TextEditScreenViewModel::class.java
    override fun createViewModel() = TextEditScreenViewModel(initialValue)

    @Composable
    override fun Content() {
        LightwaveTheme {
            LightTextInputEditor(
                title = title,
                state = viewModel.fieldState,
                onSubmit = { text -> goBack(text.toString()) },
                onBack = { goBack() },
                keyboardOptionsFlow = viewModel.keyboardOptions,
                singleLine = singleLine,
            )
        }
    }
}
