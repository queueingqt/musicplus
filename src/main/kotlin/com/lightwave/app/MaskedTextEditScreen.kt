package com.lightwave.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

class MaskedTextEditScreenViewModel(initialValue: String) : LightViewModel<String>() {
    val fieldState = TextFieldState(initialValue)
    // Reused as-is from the real SDK editor (com.thelightphone.sdk.ui.defaultKeyboardOptions)
    // — keyboard behavior is unaffected by masking, only the rendered text is.
    val keyboardOptions = MutableStateFlow<KeyboardOptions>(defaultKeyboardOptions())
}

/**
 * Password-only counterpart to [TextEditScreen] — same full-screen SDK-keyboard editing
 * flow, but renders the typed text masked instead of in plain text.
 *
 * Exists because [com.thelightphone.sdk.ui.LightTextInputEditor] (used by
 * [TextEditScreen] for every other field) has no masking hook at all — see
 * [MaskedTextInputEditor]'s file header for the full explanation and for why this has
 * to be a duplicated component rather than a parameter on the real one. Tracked at
 * tracked issue #2 — delete this file
 * (and [MaskedTextInputEditor]) once upstream adds real masking support, and switch
 * SettingsScreen's password field back to plain [TextEditScreen].
 *
 * Only used by SettingsScreen.kt's password field.
 */
class MaskedTextEditScreen(
    activity: SealedLightActivity,
    private val title: String,
    private val initialValue: String,
) : LightScreen<String, MaskedTextEditScreenViewModel>(activity) {

    override val viewModelClass = MaskedTextEditScreenViewModel::class.java
    override fun createViewModel() = MaskedTextEditScreenViewModel(initialValue)

    @Composable
    override fun Content() {
        LightwaveTheme {
            MaskedTextInputEditor(
                title = title,
                state = viewModel.fieldState,
                onSubmit = { text -> goBack(text.toString()) },
                onBack = { goBack() },
                keyboardOptionsFlow = viewModel.keyboardOptions,
                singleLine = true,
            )
        }
    }
}
