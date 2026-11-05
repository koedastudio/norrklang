package studio.koeda.norrklang.ui.signin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

        // No ContentType on purpose: autofill has no "server address"
        // type, and Username would offer credentials into a plain-text
        // URL box.
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text(stringResource(R.string.signin_server_label)) },
            placeholder = { Text(stringResource(R.string.signin_server_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.signin_username_label)) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.signin_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Password },
        )

        if (state is SignInUiState.Error) {
            Text(
                text = errorText(state),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        FormButton(
            text = stringResource(R.string.signin_connect),
            onClick = onConnect,
            busy = state is SignInUiState.Connecting,
        )
    }
}

@Composable
private fun errorText(error: SignInUiState.Error): String = when (error.kind) {
    SignInErrorKind.MISSING_FIELDS -> stringResource(R.string.signin_error_missing_fields)
    SignInErrorKind.CLEARTEXT -> stringResource(R.string.signin_error_cleartext)
    SignInErrorKind.AUTH -> stringResource(R.string.signin_error_auth)
    SignInErrorKind.NETWORK -> stringResource(R.string.signin_error_network)
    SignInErrorKind.NO_MUSIC_LIBRARY ->
        stringResource(R.string.signin_error_no_music_library)
    SignInErrorKind.GENERIC ->
        stringResource(R.string.signin_error_generic, error.detail ?: "unknown error")
}
