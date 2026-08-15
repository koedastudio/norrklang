package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.plex.ConnectionProber
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.plex.PlexClientInfo
import studio.koeda.norrklang.plex.PlexException
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexTvClient
import studio.koeda.norrklang.plex.model.PlexConnection
import studio.koeda.norrklang.plex.model.PlexResource

/**
 * Drives the Plex sign-in flow: PIN link → server pick → connection pick →
 * validate + connect. The final validated [PlexAccount] goes to
 * [SessionManager.signInPlex]; everything before that is UI-local state.
 */
@HiltViewModel
class PlexSignInViewModel internal constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val tvClientFactory: (PlexClientInfo) -> PlexTvClient,
    private val proberFactory: (PlexClientInfo) -> ConnectionProber,
    private val serverClientFactory: (String, String, PlexClientInfo) -> PlexServerClient,
    private val pollIntervalMs: Long = PIN_POLL_INTERVAL_MS,
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
        data object FetchingServers : UiState
        data class PickServer(val servers: List<PlexResource>) : UiState
        data class PickConnection(
            val server: PlexResource,
            val probes: List<ConnectionProber.ProbeResult>,
            val probing: Boolean,
        ) : UiState

        data object Validating : UiState
        data class Error(val kind: ErrorKind) : UiState
        data object Done : UiState
    }

    enum class ErrorKind { LINK_FAILED, NO_SERVERS, NO_MUSIC_LIBRARY, VALIDATION_FAILED }

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    private var cachedInfo: PlexClientInfo? = null
    private var cachedTvClient: PlexTvClient? = null
    private var cachedProber: ConnectionProber? = null
    private var accountToken: String? = null
    private var username: String = ""
    private var flowJob: Job? = null

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
                if (accountToken == null) {
                    linkAccount()
                }
                fetchServers()
            } catch (_: PlexException) {
                state = UiState.Error(
                    if (accountToken == null) ErrorKind.LINK_FAILED else ErrorKind.NO_SERVERS,
                )
            }
        }
    }

    private suspend fun linkAccount() {
        val tv = tv()
        var pin = tv.createPin()
        state = UiState.WaitingForLink(pin.code, linkUrl(pin.code))
        var pollFailures = 0
        while (true) {
            delay(pollIntervalMs)
            val checked = try {
                tv.checkPin(pin.id)
            } catch (_: PlexException.NotFound) {
                // Expired pin — mint a fresh one and keep waiting.
                pin = tv.createPin()
                state = UiState.WaitingForLink(pin.code, linkUrl(pin.code))
                continue
            } catch (e: PlexException) {
                // A dropped poll (flaky in-car connectivity) must not abort
                // a link the user is mid-way through on their phone; only a
                // sustained outage surfaces as LINK_FAILED.
                if (++pollFailures >= MAX_POLL_FAILURES) throw e
                continue
            }
            pollFailures = 0
            val token = checked.authToken
            if (!token.isNullOrEmpty()) {
                accountToken = token
                username = try {
                    tv.user(token).username
                } catch (_: PlexException) {
                    // Display-only; a failed lookup must not fail the link.
                    ""
                }
                return
            }
        }
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
            val section = try {
                serverClientFactory(uri, token, info()).use { client ->
                    client.musicSections().firstOrNull()
                }
            } catch (_: PlexException) {
                state = UiState.Error(ErrorKind.VALIDATION_FAILED)
                return@launch
            }
            if (section == null) {
                state = UiState.Error(ErrorKind.NO_MUSIC_LIBRARY)
                return@launch
            }
            val account = PlexAccount(
                serverUri = uri,
                serverName = server.name.ifEmpty { uri },
                machineIdentifier = server.clientIdentifier,
                token = token,
                sectionId = section.key,
                username = username,
            )
            sessionManager.signInPlex(account).fold(
                onSuccess = { state = UiState.Done },
                onFailure = { state = UiState.Error(ErrorKind.VALIDATION_FAILED) },
            )
        }
    }

    fun retry() {
        if (state is UiState.Error) restart()
    }

    /** Stops polling and resets to the entry state (leaving the Plex pages). */
    fun cancel() {
        flowJob?.cancel()
        flowJob = null
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

    private companion object {
        const val PIN_POLL_INTERVAL_MS = 2_000L

        /** Consecutive failed polls tolerated before the link flow gives up. */
        const val MAX_POLL_FAILURES = 5
    }
}
