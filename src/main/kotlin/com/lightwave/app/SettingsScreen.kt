package com.lightwave.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.viewModelScope
import com.lightwave.app.data.AppGraph
import com.lightwave.app.data.ServerConfig
import com.lightwave.app.data.ServerConfigRepository
import com.lightwave.app.data.SubsonicClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsScreenViewModel(
    private val serverConfigRepository: ServerConfigRepository,
) : LightViewModel<Unit>() {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    private var loadedInitial = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (loadedInitial) return
        loadedInitial = true
        viewModelScope.launch {
            val config = serverConfigRepository.serverConfig.first()
            if (config != null) {
                _baseUrl.value = config.baseUrl
                _username.value = config.username
                _password.value = config.password
            }
        }
    }

    fun onBaseUrlChange(value: String) {
        _baseUrl.value = value
    }

    fun onUsernameChange(value: String) {
        _username.value = value
    }

    fun onPasswordChange(value: String) {
        _password.value = value
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = "Testing..."
            val ok = SubsonicClient(ServerConfig(_baseUrl.value, _username.value, _password.value)).ping()
            _testResult.value = if (ok) "Connection OK" else "Connection failed"
        }
    }

    fun save() {
        viewModelScope.launch {
            serverConfigRepository.save(ServerConfig(_baseUrl.value, _username.value, _password.value))
            AppGraph.invalidateApi()
            _saveMessage.value = "Saved"
        }
    }
}

class SettingsScreen(activity: SealedLightActivity) :
    LightScreen<Unit, SettingsScreenViewModel>(activity) {

    override val viewModelClass = SettingsScreenViewModel::class.java

    override fun createViewModel() = SettingsScreenViewModel(AppGraph.from(lightContext).serverConfigRepository)

    @Composable
    override fun Content() {
        val baseUrl by viewModel.baseUrl.collectAsState()
        val username by viewModel.username.collectAsState()
        val password by viewModel.password.collectAsState()
        val testResult by viewModel.testResult.collectAsState()
        val saveMessage by viewModel.saveMessage.collectAsState()

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), center = LightTopBarCenter.Text("Settings"))

            Column(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                LightText(text = "Server URL", variant = LightTextVariant.Fine)
                // TODO: swap for the SDK's real tap-to-edit `LightTextField` +
                // `LightTextInputEditor` pattern once that component's exact API is
                // confirmed (see sdk/ui/.../LightTextInputEditor.kt and
                // LightEmbeddedLp3Keyboard.kt) — BasicTextField is a plain-Compose
                // fallback for all three fields below, not an SDK component, and it
                // doesn't mask the password field either. textStyle/cursorBrush are
                // set explicitly below because BasicTextField defaults to black text
                // — invisible against LightOS's dark theme (found on-device testing).
                BasicTextField(
                    value = baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    textStyle = TextStyle(color = LightThemeTokens.colors.content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LightThemeTokens.colors.content),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
                )

                LightText(text = "Username", variant = LightTextVariant.Fine)
                BasicTextField(
                    value = username,
                    onValueChange = viewModel::onUsernameChange,
                    textStyle = TextStyle(color = LightThemeTokens.colors.content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LightThemeTokens.colors.content),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
                )

                LightText(text = "Password", variant = LightTextVariant.Fine)
                BasicTextField(
                    value = password,
                    onValueChange = viewModel::onPasswordChange,
                    textStyle = TextStyle(color = LightThemeTokens.colors.content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LightThemeTokens.colors.content),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
                )

                LightText(
                    text = "Test connection" + (testResult?.let { " — $it" } ?: ""),
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.testConnection() }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )

                LightText(
                    text = "Save" + (saveMessage?.let { " — $it" } ?: ""),
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.save() }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )
            }
        }
    }
}
