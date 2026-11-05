package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.runSuspendCatching
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinClientInfo
import studio.koeda.norrklang.jellyfin.JellyfinException

/**
 * Drives the Jellyfin sign-in form: authenticate by name → library pick
 * (only with several music libraries) → [SessionManager.signInJellyfin].
 * The library pick rides beside the form state in [libraryPick].
 */
@HiltViewModel
class JellyfinSignInViewModel internal constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val clientFactory: (String, String?, JellyfinClientInfo) -> JellyfinClient,
    cleartextPolicy: CleartextServerPolicy,
) : CredentialsSignInViewModel(
    cleartextPolicy,
    // Jellyfin allows password-less users (the public demo's "demo" account is one).
    passwordRequired = false,
) {

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

    /** Shown when the server has several music libraries; all pre-selected. */
    data class LibraryPick(val libraries: List<MusicLibrary>, val selected: Set<String>)

    var libraryPick by mutableStateOf<LibraryPick?>(null)
        private set

    /** The authenticated account awaiting the library pick. */
    private var pendingAccount: JellyfinAccount? = null

    override suspend fun signIn(): SignInUiState {
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
                return SignInUiState.Error(SignInErrorKind.NO_MUSIC_LIBRARY, null)
            }
            // Cosmetic only — a failed lookup falls back to the URL label.
            val serverName = try {
                client.publicSystemInfo().serverName
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
            return SignInUiState.Idle
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
        if (state is SignInUiState.Connecting) return
        val pick = libraryPick ?: return
        val account = pendingAccount ?: return
        state = SignInUiState.Connecting
        viewModelScope.launch {
            state = runSuspendCatching {
                complete(account, pick.libraries.mapTo(mutableSetOf()) { it.id } - pick.selected)
            }.getOrElse { it.toSignInError() }
            // The form shows the message; the pick is rebuilt on the next connect.
            if (state is SignInUiState.Error) libraryPick = null
        }
    }

    /** Back from the picker: the form keeps its fields. */
    fun cancelLibraryPick() {
        libraryPick = null
        pendingAccount = null
        state = SignInUiState.Idle
    }

    private suspend fun complete(account: JellyfinAccount, excludedLibraryIds: Set<String>): SignInUiState =
        sessionManager.signInJellyfin(account, excludedLibraryIds).fold(
            onSuccess = {
                clearPassword()
                pendingAccount = null
                libraryPick = null
                SignInUiState.Done
            },
            onFailure = { it.toSignInError() },
        )
}
