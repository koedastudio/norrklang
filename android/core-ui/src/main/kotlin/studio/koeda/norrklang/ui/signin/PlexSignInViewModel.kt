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
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.plex.ConnectionProber
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.plex.PlexClientInfo
import studio.koeda.norrklang.plex.PlexException
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexTvClient
import studio.koeda.norrklang.plex.model.PlexConnection
import studio.koeda.norrklang.plex.model.PlexHomeUser
import studio.koeda.norrklang.plex.model.PlexResource

/**
 * Drives the Plex sign-in flow: PIN link → Home profile pick (only when the
 * account administers a Plex Home with several profiles, with a PIN step for
 * protected ones) → server pick → connection pick → library pick (only with
 * several music sections) → validate + connect. The final validated
 * [PlexAccount] and the library choice go to [SessionManager.signInPlex];
 * everything before that is UI-local state.
 */
@HiltViewModel
class PlexSignInViewModel internal constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val tvClientFactory: (PlexClientInfo) -> PlexTvClient,
    private val proberFactory: (PlexClientInfo) -> ConnectionProber,
    private val serverClientFactory: (String, String, PlexClientInfo) -> PlexServerClient,
    private val pollIntervalMs: Long = SIGN_IN_POLL_INTERVAL_MS,
) : ViewModel() {

    @Inject constructor(
        sessionManager: SessionManager,
        settings: ServerSettingsRepository,
    ) : this(
        sessionManager,
        settings,
        { info -> PlexTvClient(info) },
        { info -> ConnectionProber(info) },
        { uri, token, info -> PlexServerClient(uri, token, info) },
    )

    sealed interface UiState {
        data object Idle : UiState
        data object CreatingPin : UiState
        data class WaitingForLink(val code: String, val linkUrl: String) : UiState

        /** The Home's profiles, admin included; picking the admin needs no switch. */
        data class PickUser(val users: List<PlexHomeUser>) : UiState

        /** [wrongPin] after a rejected attempt; the page stays up for another try. */
        data class EnterPin(val user: PlexHomeUser, val wrongPin: Boolean) : UiState
        data object SwitchingUser : UiState
        data object FetchingServers : UiState
        data class PickServer(val servers: List<PlexResource>) : UiState
        data class PickConnection(
            val server: PlexResource,
            val probes: List<ConnectionProber.ProbeResult>,
            val probing: Boolean,
        ) : UiState

        /** Several music sections: all pre-selected, at least one must stay. */
        data class PickLibraries(
            val server: PlexResource,
            val connection: PlexConnection,
            val libraries: List<MusicLibrary>,
            val selected: Set<String>,
        ) : UiState

        data object Validating : UiState
        data class Error(val kind: ErrorKind) : UiState
        data object Done : UiState
    }

    enum class ErrorKind {
        LINK_FAILED, SWITCH_FAILED, NO_SERVERS, NO_MUSIC_LIBRARY, VALIDATION_FAILED,
    }

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    private var cachedInfo: PlexClientInfo? = null
    private var cachedTvClient: PlexTvClient? = null
    private var cachedProber: ConnectionProber? = null

    /** What the PIN link produced — the Home admin's identity when there is a Home. */
    private var linkedToken: String? = null
    private var linkedUsername: String = ""

    /** What the rest of the flow runs as: the linked identity or a switched profile's. */
    private var accountToken: String? = null
    private var username: String = ""
    private var homeUsers: List<PlexHomeUser> = emptyList()
    private var flowJob: Job? = null

    /** The account awaiting the library pick. */
    private var pendingAccount: PlexAccount? = null

    /** Starts (or restarts) the PIN link flow. */
    fun start() {
        if (state !is UiState.Idle && state !is UiState.Error) return
        restart()
    }

    private fun restart() {
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            state = UiState.CreatingPin
            try {
                if (linkedToken == null) {
                    linkAccount()
                }
                if (!offerHomeUsers()) fetchServers()
            } catch (_: PlexException) {
                state = UiState.Error(
                    if (linkedToken == null) ErrorKind.LINK_FAILED else ErrorKind.NO_SERVERS,
                )
            }
        }
    }

    /**
     * Shows the profile picker when the linked account administers a Plex
     * Home with more than one profile. False when there is nothing to pick,
     * including when plex.tv refuses the listing (no Home, or not its
     * admin): the flow then carries on as the linked account, exactly as
     * before Home support.
     */
    private suspend fun offerHomeUsers(): Boolean {
        val token = linkedToken ?: return false
        val users = try {
            tv().homeUsers(token)
        } catch (_: PlexException) {
            emptyList()
        }
        if (users.size < 2) return false
        homeUsers = users
        state = UiState.PickUser(users)
        return true
    }

    fun selectUser(user: PlexHomeUser) {
        if (state !is UiState.PickUser) return
        when {
            // The link already produced the admin's token.
            user.admin -> {
                accountToken = linkedToken
                username = linkedUsername
                flowJob?.cancel()
                flowJob = viewModelScope.launch { fetchServers() }
            }
            user.pinProtected -> state = UiState.EnterPin(user, wrongPin = false)
            else -> switchUser(user, pin = null)
        }
    }

    fun submitPin(pin: String) {
        val entering = state as? UiState.EnterPin ?: return
        switchUser(entering.user, pin)
    }

    /** Back from the PIN page to the profile list. */
    fun cancelPin() {
        if (state !is UiState.EnterPin) return
        state = UiState.PickUser(homeUsers)
    }

    private fun switchUser(user: PlexHomeUser, pin: String?) {
        val token = linkedToken ?: return
        state = UiState.SwitchingUser
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            val switched = try {
                tv().switchHomeUser(token, user, pin)
            } catch (_: PlexException.AuthFailed) {
                // Rejected PIN. A profile listed without a PIN can land here
                // too if one was added meanwhile; asking for it is still right.
                state = UiState.EnterPin(user, wrongPin = pin != null)
                return@launch
            } catch (_: PlexException) {
                state = UiState.Error(ErrorKind.SWITCH_FAILED)
                return@launch
            }
            accountToken = switched
            username = user.title
            fetchServers()
        }
    }

    private suspend fun linkAccount() {
        val tv = tv()
        var pin = tv.createPin()
        state = UiState.WaitingForLink(pin.code, linkUrl(pin.code))
        val token = pollUntil(pollIntervalMs, isTransient = { it is PlexException }) {
            try {
                tv.checkPin(pin.id).authToken?.takeIf { it.isNotEmpty() }
            } catch (_: PlexException.NotFound) {
                // Expired pin — mint a fresh one and keep waiting.
                pin = tv.createPin()
                state = UiState.WaitingForLink(pin.code, linkUrl(pin.code))
                null
            }
        }
        linkedToken = token
        accountToken = token
        linkedUsername = try {
            tv.user(token).username
        } catch (_: PlexException) {
            // Display-only; a failed lookup must not fail the link.
            ""
        }
        username = linkedUsername
    }

    private suspend fun fetchServers() {
        val token = accountToken ?: return
        state = UiState.FetchingServers
        val servers = try {
            tv().servers(token).filter { it.connections.isNotEmpty() }
        } catch (_: PlexException) {
            state = UiState.Error(ErrorKind.NO_SERVERS)
            return
        }
        when {
            servers.isEmpty() -> state = UiState.Error(ErrorKind.NO_SERVERS)
            servers.size == 1 -> selectServer(servers.single())
            else -> state = UiState.PickServer(servers)
        }
    }

    fun selectServer(server: PlexResource) {
        val token = accountToken ?: return
        state = UiState.PickConnection(server, probes = emptyList(), probing = true)
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            val results = prober().probe(server.connections, server.accessToken ?: token)
            // Reachable first, fastest first; relay always sinks to the bottom.
            val sorted = results.sortedWith(
                compareBy(
                    { it.latencyMs == null },
                    { it.connection.relay },
                    { it.latencyMs ?: Long.MAX_VALUE },
                ),
            )
            state = UiState.PickConnection(server, sorted, probing = false)
        }
    }

    fun selectConnection(server: PlexResource, connection: PlexConnection) {
        val token = server.accessToken ?: accountToken ?: return
        state = UiState.Validating
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            val uri = connection.uri.trimEnd('/')
            val sections = try {
                serverClientFactory(uri, token, info()).use { client ->
                    client.musicSections()
                }
            } catch (_: PlexException) {
                state = UiState.Error(ErrorKind.VALIDATION_FAILED)
                return@launch
            }
            if (sections.isEmpty()) {
                state = UiState.Error(ErrorKind.NO_MUSIC_LIBRARY)
                return@launch
            }
            val account = PlexAccount(
                serverUri = uri,
                serverName = server.name.ifEmpty { uri },
                machineIdentifier = server.clientIdentifier,
                token = token,
                username = username,
            )
            val libraries = sections.map { MusicLibrary(it.key, it.title) }
            if (libraries.size > 1) {
                pendingAccount = account
                state = UiState.PickLibraries(
                    server,
                    connection,
                    libraries,
                    selected = libraries.mapTo(mutableSetOf()) { it.id },
                )
                return@launch
            }
            signIn(account, excludedLibraryIds = emptySet())
        }
    }

    /** Refuses to empty the selection: the last library stays selected. */
    fun toggleLibrary(libraryId: String, selected: Boolean) {
        val pick = state as? UiState.PickLibraries ?: return
        val next = if (selected) pick.selected + libraryId else pick.selected - libraryId
        if (next.isEmpty()) return
        state = pick.copy(selected = next)
    }

    fun confirmLibraries() {
        val pick = state as? UiState.PickLibraries ?: return
        val account = pendingAccount ?: return
        state = UiState.Validating
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            signIn(account, pick.libraries.mapTo(mutableSetOf()) { it.id } - pick.selected)
        }
    }

    private suspend fun signIn(account: PlexAccount, excludedLibraryIds: Set<String>) {
        sessionManager.signInPlex(account, excludedLibraryIds).fold(
            onSuccess = {
                pendingAccount = null
                state = UiState.Done
            },
            onFailure = { state = UiState.Error(ErrorKind.VALIDATION_FAILED) },
        )
    }

    fun retry() {
        if (state is UiState.Error) restart()
    }

    /** Stops polling and resets to the entry state (leaving the Plex pages). */
    fun cancel() {
        flowJob?.cancel()
        flowJob = null
        pendingAccount = null
        state = UiState.Idle
    }

    override fun onCleared() {
        cachedTvClient?.close()
        cachedProber?.close()
    }

    private suspend fun info(): PlexClientInfo =
        cachedInfo ?: PlexClientInfo(settings.plexClientId(), PlexClientInfo.DEFAULT_VERSION)
            .also { cachedInfo = it }

    private suspend fun tv(): PlexTvClient =
        cachedTvClient ?: tvClientFactory(info()).also { cachedTvClient = it }

    private suspend fun prober(): ConnectionProber =
        cachedProber ?: proberFactory(info()).also { cachedProber = it }

    private fun linkUrl(code: String) = "https://plex.tv/link/?pin=$code"
}
