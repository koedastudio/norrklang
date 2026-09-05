package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.session.SessionManager

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Connecting : UiState
        data class Error(val kind: ErrorKind, val detail: String?) : UiState
        data object Done : UiState
    }

    /** Shared with [JellyfinSignInViewModel]; Subsonic never emits [ErrorKind.NO_MUSIC_LIBRARY]. */
    enum class ErrorKind { MISSING_FIELDS, AUTH, NETWORK, NO_MUSIC_LIBRARY, GENERIC }

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
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            state = UiState.Error(ErrorKind.MISSING_FIELDS, null)
            return
        }
        state = UiState.Connecting
        viewModelScope.launch {
            val result = sessionManager.signIn(serverUrl, username, password)
            state = result.fold(
                onSuccess = {
                    // Only the derived (salt, token) pair is persisted — don't
                    // let the plaintext password linger for the ViewModel's
                    // lifetime.
                    password = ""
                    UiState.Done
                },
                onFailure = { e ->
                    when (e) {
                        is MusicException.AuthFailed ->
                            UiState.Error(ErrorKind.AUTH, e.message)
                        is MusicException.NetworkError ->
                            UiState.Error(ErrorKind.NETWORK, e.message)
                        else ->
                            UiState.Error(ErrorKind.GENERIC, e.message)
                    }
                },
            )
        }
    }
}
