package com.musicplus.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.ServerConfig
import com.musicplus.app.data.ServerConfigRepository
import com.musicplus.app.data.ServerProfile
import com.musicplus.app.data.SubsonicClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Add or edit one [ServerProfile] (multi-server support). [serverId] null means
 * "creating a new one" (a fresh id is minted on save); non-null loads and edits
 * that existing profile. Reached from [ServerSettingsScreen]'s list (tap a row
 * to edit, its top-bar ADD icon to create).
 */
class ServerEditScreenViewModel(
    private val serverConfigRepository: ServerConfigRepository,
    private val serverId: String?,
) : LightViewModel<Unit>() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

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

    val isNew: Boolean get() = serverId == null

    private var loadedInitial = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (loadedInitial || serverId == null) return
        loadedInitial = true
        viewModelScope.launch {
            val existing = serverConfigRepository.servers.first().find { it.id == serverId } ?: return@launch
            _name.value = existing.name
            _baseUrl.value = existing.baseUrl
            _username.value = existing.username
            _password.value = existing.password
        }
    }

    fun onNameChange(value: String) { _name.value = value }
    fun onBaseUrlChange(value: String) { _baseUrl.value = value }
    fun onUsernameChange(value: String) { _username.value = value }
    fun onPasswordChange(value: String) { _password.value = value }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = "Testing..."
            val result = SubsonicClient(ServerConfig(_baseUrl.value, _username.value, _password.value)).ping()
            _testResult.value = result.fold(
                onSuccess = { "Connection OK" },
                onFailure = { "Failed: ${it::class.simpleName}: ${it.message}" },
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            val profile = ServerProfile(
                id = serverId ?: UUID.randomUUID().toString(),
                name = _name.value.ifBlank { _baseUrl.value },
                baseUrl = _baseUrl.value.trimEnd('/'),
                username = _username.value,
                password = _password.value,
            )
            serverConfigRepository.addOrUpdate(profile)
            AppGraph.invalidateApi()
            _saveMessage.value = "Saved"
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = serverId ?: return
        viewModelScope.launch {
            serverConfigRepository.remove(id)
            AppGraph.invalidateApi()
            onDeleted()
        }
    }
}

class ServerEditScreen(activity: SealedLightActivity, private val serverId: String?) :
    LightScreen<Unit, ServerEditScreenViewModel>(activity) {

    override val viewModelClass = ServerEditScreenViewModel::class.java

    override fun createViewModel() =
        ServerEditScreenViewModel(AppGraph.from(lightContext).serverConfigRepository, serverId)

    @Composable
    override fun Content() {
        val name by viewModel.name.collectAsState()
        val baseUrl by viewModel.baseUrl.collectAsState()
        val username by viewModel.username.collectAsState()
        val password by viewModel.password.collectAsState()
        val testResult by viewModel.testResult.collectAsState()
        val saveMessage by viewModel.saveMessage.collectAsState()

        MusicPlusScaffold(
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(if (viewModel.isNew) "Add server" else "Edit server"),
                )
            },
            onMiniPlayerClick = { navigateTo(::PlayerScreen) },
            onSleepTimerClick = { navigateTo(::SleepTimerPickerScreen) },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                LightTextField(
                    label = "Name",
                    value = name,
                    placeholder = "e.g. Home",
                    onClick = {
                        navigateTo({ a -> TextEditScreen(a, "Server name", name) }) { result ->
                            viewModel.onNameChange(result)
                        }
                    },
                )

                // Real LightOS pattern: a read-only LightTextField that opens the SDK's
                // own full-screen LightTextInputEditor (with its embedded LP3 keyboard)
                // on tap, instead of a raw system-IME text field. See TextEditScreen.kt.
                LightTextField(
                    label = "Server URL",
                    value = baseUrl,
                    placeholder = "https://music.example.com",
                    onClick = {
                        navigateTo({ a -> TextEditScreen(a, "Server URL", baseUrl) }) { result ->
                            viewModel.onBaseUrlChange(result)
                        }
                    },
                )

                LightTextField(
                    label = "Username",
                    value = username,
                    placeholder = "Username",
                    onClick = {
                        navigateTo({ a -> TextEditScreen(a, "Username", username) }) { result ->
                            viewModel.onUsernameChange(result)
                        }
                    },
                )

                // Same tap-to-edit pattern as the fields above, but opens
                // MaskedTextEditScreen (not TextEditScreen) so the password is masked
                // while actively being typed, not just in this read-only summary.
                // See MaskedTextInputEditor.kt's file header for why a separate,
                // duplicated editor was needed — tracked issue #2
                LightTextField(
                    label = "Password",
                    value = if (password.isBlank()) "" else "•".repeat(password.length),
                    placeholder = "Password",
                    onClick = {
                        navigateTo({ a -> MaskedTextEditScreen(a, "Password", password) }) { result ->
                            viewModel.onPasswordChange(result)
                        }
                    },
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
                        .lightClickable { viewModel.save { goBack() } }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )

                if (!viewModel.isNew) {
                    LightText(
                        text = "Delete server",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.delete { goBack() } }
                            .padding(vertical = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
