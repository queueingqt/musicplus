package com.musicplus.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.musicplus.app.data.AppGraph
import com.musicplus.app.data.DownloadSummary
import com.musicplus.app.data.LoginResult
import com.musicplus.app.data.SaveOutcome
import com.musicplus.app.data.ServerConfigRepository
import com.musicplus.app.data.ServerDraft
import com.musicplus.app.data.ServerKind
import com.musicplus.app.data.ServerLifecycle
import com.musicplus.app.data.ServerLogin
import com.musicplus.app.data.ServerProfile
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Add or edit one [ServerProfile] (multi-server support). [serverId] null means
 * "creating a new one" (a fresh id is minted on save); non-null loads and edits
 * that existing profile. Reached from [ServerSettingsScreen]'s list (tap a row
 * to edit, its top-bar ADD icon to create).
 *
 * [kind] can only be chosen for a brand-new server — an existing profile's kind never changes (there is no sensible
 * "convert this Subsonic login into a Jellyfin one" operation; delete and re-add instead).
 */
class ServerEditScreenViewModel(
    private val serverConfigRepository: ServerConfigRepository,
    private val serverLifecycle: ServerLifecycle,
    private val serverLogin: ServerLogin,
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

    private val _kind = MutableStateFlow(ServerKind.SUBSONIC)
    val kind: StateFlow<ServerKind> = _kind.asStateFlow()

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
            _kind.value = existing.kind
        }
    }

    fun onNameChange(value: String) { _name.value = value }
    fun onBaseUrlChange(value: String) { _baseUrl.value = value }
    fun onUsernameChange(value: String) { _username.value = value }
    fun onPasswordChange(value: String) { _password.value = value }
    fun onKindChange(value: ServerKind) {
        _kind.value = value
        _testResult.value = null
    }

    private fun draft() = ServerDraft(_kind.value, _name.value, _baseUrl.value, _username.value, _password.value)

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = "Testing..."
            _testResult.value = when (val result = serverLogin.test(draft())) {
                is LoginResult.Accepted -> "Connected, login OK"
                is LoginResult.Rejected -> "Login rejected: ${result.reason}"
                is LoginResult.Failed -> "Failed: ${result.error::class.simpleName}: ${result.error.message}"
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val outcome = serverLifecycle.save(serverId, draft())) {
                SaveOutcome.Saved -> {
                    _saveMessage.value = "Saved"
                    onSaved()
                }
                is SaveOutcome.Refused -> _saveMessage.value = when (val login = outcome.login) {
                    is LoginResult.Rejected -> "Login rejected: ${login.reason}"
                    is LoginResult.Failed -> "Couldn't save: ${login.error.message}"
                    is LoginResult.Accepted -> "Couldn't save"
                }
            }
        }
    }

    /** What this server has downloaded, for the delete choice. */
    val downloadSummary: StateFlow<DownloadSummary?> =
        serverLifecycle.downloadSummaries.map { summaries -> serverId?.let { summaries[it] } }
            .screenState(viewModelScope, null)

    // On the app's own scope (see ServerLifecycle), not this screen's.
    fun remove(keepDownloads: Boolean) {
        val id = serverId ?: return
        serverLifecycle.remove(id, keepDownloads)
    }
}

class ServerEditScreen(activity: SealedLightActivity, private val serverId: String?) :
    LightScreen<Unit, ServerEditScreenViewModel>(activity) {

    override val viewModelClass = ServerEditScreenViewModel::class.java

    override fun createViewModel() =
        AppGraph.from(lightContext).let { ServerEditScreenViewModel(it.serverConfigRepository, it.serverLifecycle, it.serverLogin, serverId) }

    @Composable
    override fun Content() {
        val name by viewModel.name.collectAsState()
        val baseUrl by viewModel.baseUrl.collectAsState()
        val username by viewModel.username.collectAsState()
        val password by viewModel.password.collectAsState()
        val kind by viewModel.kind.collectAsState()
        val testResult by viewModel.testResult.collectAsState()
        val saveMessage by viewModel.saveMessage.collectAsState()

        MusicPlusScaffold(
            screen = this,
            topBar = {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(if (viewModel.isNew) "Add server" else "Edit server"),
                )
            },
        ) {
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(1f.gridUnitsAsDp())) {
                if (viewModel.isNew) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 1f.gridUnitsAsDp())) {
                        ServerKindOption("Subsonic", kind == ServerKind.SUBSONIC) { viewModel.onKindChange(ServerKind.SUBSONIC) }
                        ServerKindOption("Jellyfin", kind == ServerKind.JELLYFIN) { viewModel.onKindChange(ServerKind.JELLYFIN) }
                    }
                } else {
                    LightText(
                        text = if (kind == ServerKind.JELLYFIN) "Jellyfin server" else "Subsonic server",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                }

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
                    placeholder = if (kind == ServerKind.JELLYFIN) "http://jellyfin.example.com:8096" else "https://music.example.com",
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
                            .lightClickable {
                                navigateTo({ a ->
                                    ActionsMenuScreen(
                                        activity = a,
                                        subtitle = "Delete \"$name\"",
                                        items = serverRemovalItems(name, viewModel.downloadSummary.value) { keep -> viewModel.remove(keep) },
                                    )
                                })
                            }
                            .padding(vertical = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

/** One of the two "which kind of server is this" choices — a plain text row (checked with a leading mark), matching [SelectableRow]'s look without pulling in its single-selected-row-in-a-list shape for what's really two side-by-side options. */
@Composable
private fun ServerKindOption(label: String, selected: Boolean, onClick: () -> Unit) {
    LightText(
        text = if (selected) "◉ $label" else "○ $label",
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(end = 2f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
}
