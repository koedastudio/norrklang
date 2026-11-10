package studio.koeda.norrklang.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.signin.JellyfinSignInViewModel.Step
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/*
 * The Jellyfin half of the sign-in flow: one page per
 * JellyfinSignInViewModel.Step. Parked-only on AAOS, like SignInScreen.
 */

@Composable
internal fun JellyfinSignInPage(
    step: Step,
    state: SignInUiState,
    serverUrl: String,
    username: String,
    password: String,
    serverName: String?,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onContinue: () -> Unit,
    onUsePassword: () -> Unit,
    onRetry: () -> Unit,
    onConnect: () -> Unit,
    onToggleLibrary: (String, Boolean) -> Unit,
    onConfirmLibraries: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    when (step) {
        Step.Server -> FormPage(modifier = modifier, onBack = onBack) {
            FormTitle(stringResource(R.string.signin_title))
            FormSubtitle(stringResource(R.string.signin_subtitle_jellyfin))
            ServerUrlField(serverUrl, onServerUrlChange)
            if (state is SignInUiState.Error) {
                SignInErrorText(state, stringResource(R.string.signin_error_missing_server))
            }
            FormButton(
                text = stringResource(R.string.signin_continue),
                onClick = onContinue,
                busy = state is SignInUiState.Connecting,
            )
        }

        is Step.QuickConnect -> FormPage(modifier = modifier, onBack = onBack) {
            QuickConnectBody(step.code, state, onRetry, onUsePassword)
        }

        Step.Credentials -> FormPage(modifier = modifier, onBack = onBack) {
            FormTitle(stringResource(R.string.signin_title))
            FormSubtitle(stringResource(R.string.jellyfin_credentials_subtitle, serverName ?: serverUrl))
            UsernameField(username, onUsernameChange)
            PasswordField(password, onPasswordChange)
            if (state is SignInUiState.Error) {
                SignInErrorText(state, stringResource(R.string.signin_error_missing_username))
            }
            FormButton(
                text = stringResource(R.string.signin_connect),
                onClick = onConnect,
                busy = state is SignInUiState.Connecting,
            )
        }

        is Step.Libraries -> LibraryPickerPage(
            libraries = step.libraries,
            selectedIds = step.selected,
            onToggle = onToggleLibrary,
            onContinue = onConfirmLibraries,
            continuing = state is SignInUiState.Connecting,
            onBack = onBack,
            modifier = modifier,
            error = state as? SignInUiState.Error,
        )
    }
}

@Composable
private fun QuickConnectBody(
    code: String,
    state: SignInUiState,
    onRetry: () -> Unit,
    onUsePassword: () -> Unit,
) {
    val dimens = LocalFormDimens.current
    FormTitle(stringResource(R.string.jellyfin_quick_connect_title))
    FormSubtitle(stringResource(R.string.jellyfin_quick_connect_instruction))
    Text(
        text = code.toCharArray().joinToString(" "),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    when (state) {
        is SignInUiState.Error -> {
            SignInErrorText(state, stringResource(R.string.signin_error_missing_fields))
            FormButton(text = stringResource(R.string.signin_retry), onClick = onRetry)
        }

        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing / 2),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(
                    // Idle is waiting for the approval; Connecting is the token exchange and library lookup.
                    if (state is SignInUiState.Connecting) R.string.signin_connecting
                    else R.string.jellyfin_quick_connect_waiting,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    FormSecondaryButton(text = stringResource(R.string.jellyfin_use_password), onClick = onUsePassword)
}
