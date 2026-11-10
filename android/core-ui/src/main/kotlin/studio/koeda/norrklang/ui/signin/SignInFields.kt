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

/*
 * The sign-in forms' fields and error line, shared by the Subsonic form and
 * the Jellyfin pages.
 */

/** No ContentType on purpose: autofill has no "server address" type, and Username would offer credentials into a plain-text URL box. */
@Composable
internal fun ServerUrlField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.signin_server_label)) },
        placeholder = { Text(stringResource(R.string.signin_server_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UsernameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.signin_username_label)) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Username },
    )
}

@Composable
internal fun PasswordField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.signin_password_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
    )
}

/** [missingFieldsText] names what the page needs, since each page asks for different fields. */
@Composable
internal fun SignInErrorText(error: SignInUiState.Error, missingFieldsText: String) {
    Text(
        text = errorText(error, missingFieldsText),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun errorText(error: SignInUiState.Error, missingFieldsText: String): String = when (error.kind) {
    SignInErrorKind.MISSING_FIELDS -> missingFieldsText
    SignInErrorKind.CLEARTEXT -> stringResource(R.string.signin_error_cleartext)
    SignInErrorKind.AUTH -> stringResource(R.string.signin_error_auth)
    SignInErrorKind.NETWORK -> stringResource(R.string.signin_error_network)
    SignInErrorKind.NO_MUSIC_LIBRARY ->
        stringResource(R.string.signin_error_no_music_library)
    SignInErrorKind.GENERIC ->
        stringResource(R.string.signin_error_generic, error.detail ?: "unknown error")
}
