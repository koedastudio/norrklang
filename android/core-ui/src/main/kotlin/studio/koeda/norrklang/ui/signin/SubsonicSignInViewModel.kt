package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.runSuspendCatching
import studio.koeda.norrklang.data.session.SessionManager

@HiltViewModel
class SubsonicSignInViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    cleartextPolicy: CleartextServerPolicy,
) : CredentialsSignInViewModel(cleartextPolicy, passwordRequired = true) {

    /** Kept apart from [state] so a failed demo sign-in doesn't show as an error on the form. */
    var demoState by mutableStateOf<SignInUiState>(SignInUiState.Idle)
        private set

    /** Signs in to the public demo server; also the working login Play review needs. */
    fun tryDemo() {
        if (demoState is SignInUiState.Connecting) return
        demoState = SignInUiState.Connecting
        viewModelScope.launch {
            demoState = runSuspendCatching {
                sessionManager.signIn(DemoServer.credentials).getOrThrow()
                SignInUiState.Done
            }.getOrElse { it.toSignInError() }
        }
    }

    override suspend fun signIn(): SignInUiState =
        sessionManager.signIn(serverUrl, username, password).fold(
            onSuccess = {
                clearPassword()
                SignInUiState.Done
            },
            onFailure = { it.toSignInError() },
        )
}
