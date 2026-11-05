package studio.koeda.norrklang.ui.signin

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import studio.koeda.norrklang.data.session.SessionManager

@HiltViewModel
class SubsonicSignInViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    cleartextPolicy: CleartextServerPolicy,
) : CredentialsSignInViewModel(cleartextPolicy, passwordRequired = true) {

    override suspend fun signIn(): SignInUiState =
        sessionManager.signIn(serverUrl, username, password).fold(
            onSuccess = {
                clearPassword()
                SignInUiState.Done
            },
            onFailure = { it.toSignInError() },
        )
}
