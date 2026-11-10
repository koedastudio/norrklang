package studio.koeda.norrklang.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.ui.R

/**
 * Server connect / sign-in form. Parked-only on AAOS (no distractionOptimized
 * flag by design); a normal screen on the phone.
 *
 * [onBack] renders an explicit back affordance — required on AAOS, where the
 * system bar offers no reliable way to leave a full-screen activity.
 */
@Composable
internal fun SignInScreen(
    serverUrl: String,
    username: String,
    password: String,
    state: SignInUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String = stringResource(R.string.signin_subtitle),
) {
    FormPage(modifier = modifier, onBack = onBack) {
        FormTitle(stringResource(R.string.signin_title))
        FormSubtitle(subtitle)
        ServerUrlField(serverUrl, onServerUrlChange)
        UsernameField(username, onUsernameChange)
        PasswordField(password, onPasswordChange)

        if (state is SignInUiState.Error) {
            SignInErrorText(state, stringResource(R.string.signin_error_missing_fields))
        }

        FormButton(
            text = stringResource(R.string.signin_connect),
            onClick = onConnect,
            busy = state is SignInUiState.Connecting,
        )
    }
}
