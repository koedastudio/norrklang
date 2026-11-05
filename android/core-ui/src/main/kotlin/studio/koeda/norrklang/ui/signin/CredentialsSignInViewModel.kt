package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.runSuspendCatching

/**
 * The server/username/password form behind the Subsonic and Jellyfin
 * sign-ins: the fields, the pre-flight checks and the busy/error state.
 * The provider supplies [signIn].
 */
abstract class CredentialsSignInViewModel(
    private val cleartextPolicy: CleartextServerPolicy,
    private val passwordRequired: Boolean,
) : ViewModel() {

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var state by mutableStateOf<SignInUiState>(SignInUiState.Idle)
        protected set

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
        if (state is SignInUiState.Connecting) return
        if (serverUrl.isBlank() || username.isBlank() || (passwordRequired && password.isBlank())) {
            state = SignInUiState.Error(SignInErrorKind.MISSING_FIELDS, null)
            return
        }
        if (cleartextPolicy.rejects(serverUrl)) {
            state = SignInUiState.Error(SignInErrorKind.CLEARTEXT, null)
            return
        }
        state = SignInUiState.Connecting
        viewModelScope.launch {
            state = runSuspendCatching { signIn() }.getOrElse { it.toSignInError() }
        }
    }

    /** The provider's sign-in with the current fields; a thrown error renders as [SignInUiState.Error]. */
    protected abstract suspend fun signIn(): SignInUiState

    /** Once the session owns what it persisted, the plaintext password must not linger. */
    protected fun clearPassword() {
        password = ""
    }
}
