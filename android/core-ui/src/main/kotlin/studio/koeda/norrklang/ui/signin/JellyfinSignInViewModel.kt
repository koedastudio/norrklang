package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinClientInfo
import studio.koeda.norrklang.jellyfin.JellyfinException
import studio.koeda.norrklang.ui.signin.SignInViewModel.ErrorKind
import studio.koeda.norrklang.ui.signin.SignInViewModel.UiState

/**
 * Drives the Jellyfin sign-in form: authenticate by name → pick the first
 * music library → [SessionManager.signInJellyfin]. Reuses [SignInViewModel]'s
 * state types so the shared form renders both providers.
 */
@HiltViewModel
class JellyfinSignInViewModel internal constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val clientFactory: (String, String?, JellyfinClientInfo) -> JellyfinClient,
) : ViewModel() {

    @Inject constructor(
        sessionManager: SessionManager,
        settings: ServerSettingsRepository,
    ) : this(
        sessionManager,
        settings,
        { baseUrl, token, info -> JellyfinClient(baseUrl, token, info) },
    )

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    fun onServerUrlChange(value: String) {
        serverUrl = value
    }

    fun onUsernameChange(value: String) {
        username = value
    }

    fun onPasswordChange(value: String) {
        password = value
    }

    fun connect() {
        if (state is UiState.Connecting) return
        // Password may be blank — Jellyfin allows password-less users (the
        // public demo's "demo" account is one).
        if (serverUrl.isBlank() || username.isBlank()) {
            state = UiState.Error(ErrorKind.MISSING_FIELDS, null)
            return
        }
        state = UiState.Connecting
        viewModelScope.launch {
            state = try {
                signIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: JellyfinException) {
                when (e) {
                    is JellyfinException.AuthFailed -> UiState.Error(ErrorKind.AUTH, e.message)
                    is JellyfinException.NetworkError ->
                        UiState.Error(ErrorKind.NETWORK, e.message)
                    else -> UiState.Error(ErrorKind.GENERIC, e.message)
                }
            } catch (e: Exception) {
                UiState.Error(ErrorKind.GENERIC, e.message)
            }
        }
    }

    private suspend fun signIn(): UiState {
        val base = JellyfinAccount.normalizeBaseUrl(serverUrl)
        val info =
            JellyfinClientInfo(settings.jellyfinDeviceId(), JellyfinClientInfo.DEFAULT_VERSION)
        val auth = clientFactory(base, null, info).use { client ->
            client.authenticate(username.trim(), password)
        }
        val account = clientFactory(base, auth.accessToken, info).use { client ->
            val library = client.musicLibraries(auth.user.id).firstOrNull { it.id != null }
                ?: return UiState.Error(ErrorKind.NO_MUSIC_LIBRARY, null)
            // Cosmetic only — a failed lookup falls back to the URL label.
            val serverName = try {
                client.publicSystemInfo().serverName
            } catch (e: CancellationException) {
                throw e
            } catch (_: JellyfinException) {
                null
            }
            JellyfinAccount(
                baseUrl = base,
                serverName = serverName?.takeIf { it.isNotBlank() } ?: base,
                userId = auth.user.id,
                username = auth.user.name.ifBlank { username.trim() },
                token = auth.accessToken.orEmpty(),
                libraryId = library.id.orEmpty(),
            )
        }
        return sessionManager.signInJellyfin(account).fold(
            onSuccess = {
                // Only the token is persisted — don't let the plaintext
                // password linger for the ViewModel's lifetime.
                password = ""
                UiState.Done
            },
            onFailure = { e ->
                when (e) {
                    is MusicException.AuthFailed -> UiState.Error(ErrorKind.AUTH, e.message)
                    is MusicException.NetworkError -> UiState.Error(ErrorKind.NETWORK, e.message)
                    else -> UiState.Error(ErrorKind.GENERIC, e.message)
                }
            },
        )
    }
}
