package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.runSuspendCatching
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinClientInfo
import studio.koeda.norrklang.jellyfin.JellyfinException
import studio.koeda.norrklang.jellyfin.model.JellyfinAuthResult

/**
 * Drives the Jellyfin sign-in: server address → Quick Connect (when the
 * server has it on; the code is approved in another Jellyfin client) or the
 * password form → library pick (only with several music libraries) →
 * [SessionManager.signInJellyfin]. [step] is the page; [state] is the
 * busy/error state on it, shared with the Subsonic form so the picker and
 * the error texts are the same.
 */
@HiltViewModel
class JellyfinSignInViewModel internal constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val clientFactory: (String, String?, JellyfinClientInfo) -> JellyfinClient,
    private val cleartextPolicy: CleartextServerPolicy,
    private val pollIntervalMs: Long = SIGN_IN_POLL_INTERVAL_MS,
    private val pollTimeoutMs: Long = SIGN_IN_POLL_TIMEOUT_MS,
) : ViewModel() {

    @Inject constructor(
        sessionManager: SessionManager,
        settings: ServerSettingsRepository,
        cleartextPolicy: CleartextServerPolicy,
    ) : this(
        sessionManager,
        settings,
        { baseUrl, token, info -> JellyfinClient(baseUrl, token, info) },
        cleartextPolicy,
    )

    sealed interface Step {
        data object Server : Step

        /** Polling for approval of [code]; [SignInUiState.Idle] here means waiting. */
        data class QuickConnect(val code: String) : Step
        data object Credentials : Step

        /** All libraries pre-selected. */
        data class Libraries(val libraries: List<MusicLibrary>, val selected: Set<String>) : Step
    }

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var step by mutableStateOf<Step>(Step.Server)
        private set
    var state by mutableStateOf<SignInUiState>(SignInUiState.Idle)
        private set

    /** The probed server's display name, for labels; null until the server step passed. */
    var serverName by mutableStateOf<String?>(null)
        private set

    /** Normalized server URL, set once the server step passed. */
    private var baseUrl = ""

    /** The authenticated account awaiting the library pick. */
    private var pendingAccount: JellyfinAccount? = null
    private var flowJob: Job? = null

    fun onServerUrlChange(value: String) {
        serverUrl = value
    }

    fun onUsernameChange(value: String) {
        username = value
    }

    fun onPasswordChange(value: String) {
        password = value
    }

    /** Probes the server, then goes to Quick Connect or the password form. */
    fun continueWithServer() {
        if (state is SignInUiState.Connecting) return
        if (serverUrl.isBlank()) {
            state = SignInUiState.Error(SignInErrorKind.MISSING_FIELDS, null)
            return
        }
        if (cleartextPolicy.rejects(serverUrl)) {
            state = SignInUiState.Error(SignInErrorKind.CLEARTEXT, null)
            return
        }
        launchFlow {
            baseUrl = JellyfinAccount.normalizeBaseUrl(serverUrl)
            // A failed probe is the "wrong address" error on this page; a
            // Jellyfin server always answers /System/Info/Public.
            val quickConnect = anonymousClient().use { client ->
                serverName = client.publicSystemInfo().serverName?.takeIf { it.isNotBlank() }
                client.quickConnectEnabled()
            }
            if (quickConnect) quickConnect() else credentialsStep()
        }
    }

    /** From a Quick Connect error: a fresh code. */
    fun retry() {
        if (step !is Step.QuickConnect || state !is SignInUiState.Error) return
        launchFlow { quickConnect() }
    }

    /** Leaves Quick Connect for the password form; polling stops. */
    fun usePassword() {
        flowJob?.cancel()
        state = credentialsStep()
    }

    fun connectWithCredentials() {
        if (state is SignInUiState.Connecting) return
        // Jellyfin allows password-less users (the public demo's "demo" account is one).
        if (username.isBlank()) {
            state = SignInUiState.Error(SignInErrorKind.MISSING_FIELDS, null)
            return
        }
        launchFlow {
            val auth = anonymousClient().use { it.authenticate(username.trim(), password) }
            authenticated(auth)
        }
    }

    /** Refuses to empty the selection: the last library stays selected. */
    fun toggleLibrary(libraryId: String, selected: Boolean) {
        val pick = step as? Step.Libraries ?: return
        val next = if (selected) pick.selected + libraryId else pick.selected - libraryId
        if (next.isEmpty()) return
        step = pick.copy(selected = next)
    }

    fun confirmLibraries() {
        if (state is SignInUiState.Connecting) return
        val pick = step as? Step.Libraries ?: return
        val account = pendingAccount ?: return
        launchFlow {
            complete(account, pick.libraries.mapTo(mutableSetOf()) { it.id } - pick.selected)
        }
    }

    /** Back from the picker: both sign-in paths need redoing, so back to the server page. */
    fun cancelLibraryPick() {
        pendingAccount = null
        step = Step.Server
        state = SignInUiState.Idle
    }

    /** Stops polling and resets to the server page (leaving the Jellyfin pages); fields are kept. */
    fun cancel() {
        flowJob?.cancel()
        flowJob = null
        pendingAccount = null
        serverName = null
        step = Step.Server
        state = SignInUiState.Idle
    }

    /** Runs [block] as the single in-flight step; a thrown error renders on the current page. */
    private fun launchFlow(block: suspend () -> SignInUiState) {
        flowJob?.cancel()
        state = SignInUiState.Connecting
        flowJob = viewModelScope.launch {
            state = runSuspendCatching { block() }.getOrElse { it.toSignInError() }
        }
    }

    private fun credentialsStep(): SignInUiState {
        step = Step.Credentials
        return SignInUiState.Idle
    }

    private suspend fun quickConnect(): SignInUiState = anonymousClient().use { client ->
        var request = try {
            client.initiateQuickConnect()
        } catch (_: JellyfinException.AuthFailed) {
            // Turned off since the probe said it was on.
            return credentialsStep()
        }
        step = Step.QuickConnect(request.code)
        state = SignInUiState.Idle
        val secret = pollUntil(pollIntervalMs, pollTimeoutMs, isTransient = { it is JellyfinException }) {
            try {
                request.secret.takeIf { client.quickConnectState(it).authenticated }
            } catch (_: JellyfinException.NotFound) {
                // Expired request — start a fresh one and keep waiting.
                request = client.initiateQuickConnect()
                step = Step.QuickConnect(request.code)
                null
            }
        }
        state = SignInUiState.Connecting
        authenticated(client.authenticateWithQuickConnect(secret))
    }

    /** Shared tail of both paths: libraries → pick or straight to the session. */
    private suspend fun authenticated(auth: JellyfinAuthResult): SignInUiState {
        val libraries = clientFactory(baseUrl, auth.accessToken, clientInfo()).use { client ->
            client.musicLibraries(auth.user.id)
                .mapNotNull { view -> view.id?.let { MusicLibrary(it, view.name) } }
        }
        if (libraries.isEmpty()) {
            return SignInUiState.Error(SignInErrorKind.NO_MUSIC_LIBRARY, null)
        }
        val account = JellyfinAccount(
            baseUrl = baseUrl,
            serverName = serverName ?: baseUrl,
            userId = auth.user.id,
            username = auth.user.name.ifBlank { username.trim() },
            token = auth.accessToken.orEmpty(),
        )
        if (libraries.size > 1) {
            pendingAccount = account
            step = Step.Libraries(libraries, libraries.mapTo(mutableSetOf()) { it.id })
            return SignInUiState.Idle
        }
        return complete(account, excludedLibraryIds = emptySet())
    }

    private suspend fun complete(account: JellyfinAccount, excludedLibraryIds: Set<String>): SignInUiState =
        sessionManager.signInJellyfin(account, excludedLibraryIds).fold(
            onSuccess = {
                // Once the session owns what it persisted, the plaintext password must not linger.
                password = ""
                pendingAccount = null
                SignInUiState.Done
            },
            onFailure = { it.toSignInError() },
        )

    private suspend fun clientInfo() =
        JellyfinClientInfo(settings.jellyfinDeviceId(), JellyfinClientInfo.DEFAULT_VERSION)

    private suspend fun anonymousClient() = clientFactory(baseUrl, null, clientInfo())
}
