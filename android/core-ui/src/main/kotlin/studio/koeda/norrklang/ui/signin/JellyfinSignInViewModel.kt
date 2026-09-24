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
import studio.koeda.norrklang.data.model.MusicLibrary
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
 * Drives the Jellyfin sign-in form: authenticate by name → library pick
 * (only with several music libraries) → [SessionManager.signInJellyfin].
 * Reuses [SignInViewModel]'s state types so the shared form renders both
 * providers; the library pick rides beside them in [libraryPick].
 */
@HiltViewModel
class JellyfinSignInViewModel internal constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val clientFactory: (String, String?, JellyfinClientInfo) -> JellyfinClient,
    private val cleartextPolicy: CleartextServerPolicy,
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

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    /** Shown when the server has several music libraries; all pre-selected. */
    data class LibraryPick(val libraries: List<MusicLibrary>, val selected: Set<String>)

    var libraryPick by mutableStateOf<LibraryPick?>(null)
        private set

    /** The authenticated account awaiting the library pick. */
    private var pendingAccount: JellyfinAccount? = null

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
        if (cleartextPolicy.rejects(serverUrl)) {
            state = UiState.Error(ErrorKind.CLEARTEXT, null)
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
        val (account, libraries) = clientFactory(base, auth.accessToken, info).use { client ->
            val libraries = client.musicLibraries(auth.user.id)
                .mapNotNull { view -> view.id?.let { MusicLibrary(it, view.name) } }
            if (libraries.isEmpty()) {
                return UiState.Error(ErrorKind.NO_MUSIC_LIBRARY, null)
            }
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
            ) to libraries
        }
        if (libraries.size > 1) {
            pendingAccount = account
            libraryPick = LibraryPick(libraries, libraries.mapTo(mutableSetOf()) { it.id })
            return UiState.Idle
        }
        return complete(account, excludedLibraryIds = emptySet())
    }

    /** Refuses to empty the selection: the last library stays selected. */
    fun toggleLibrary(libraryId: String, selected: Boolean) {
        val pick = libraryPick ?: return
        val next = if (selected) pick.selected + libraryId else pick.selected - libraryId
        if (next.isEmpty()) return
        libraryPick = pick.copy(selected = next)
    }

    fun confirmLibraries() {
        if (state is UiState.Connecting) return
        val pick = libraryPick ?: return
        val account = pendingAccount ?: return
        state = UiState.Connecting
        viewModelScope.launch {
            state = try {
                complete(account, pick.libraries.mapTo(mutableSetOf()) { it.id } - pick.selected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(ErrorKind.GENERIC, e.message)
            }
            // The form shows the message; the pick is rebuilt on the next connect.
            if (state is UiState.Error) libraryPick = null
        }
    }

    /** Back from the picker: the form keeps its fields. */
    fun cancelLibraryPick() {
        libraryPick = null
        pendingAccount = null
        state = UiState.Idle
    }

    private suspend fun complete(account: JellyfinAccount, excludedLibraryIds: Set<String>): UiState =
        sessionManager.signInJellyfin(account, excludedLibraryIds).fold(
            onSuccess = {
                // Only the token is persisted — don't let the plaintext
                // password linger for the ViewModel's lifetime.
                password = ""
                pendingAccount = null
                libraryPick = null
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
