package studio.koeda.norrklang.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.plex.model.PlexConnection
import studio.koeda.norrklang.plex.model.PlexHomeUser
import studio.koeda.norrklang.plex.model.PlexResource
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.LibraryChecklist
import studio.koeda.norrklang.ui.components.QrLinkSection
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/*
 * The Plex half of the sign-in flow: one scaffold, one page body per
 * PlexSignInViewModel.UiState. Parked-only on AAOS, like SignInScreen.
 */

@Composable
internal fun PlexSignInPage(
    state: PlexSignInViewModel.UiState,
    onSelectUser: (PlexHomeUser) -> Unit,
    onSubmitPin: (String) -> Unit,
    onCancelPin: () -> Unit,
    onSelectServer: (PlexResource) -> Unit,
    onSelectConnection: (PlexResource, PlexConnection) -> Unit,
    onToggleLibrary: (String, Boolean) -> Unit,
    onConfirmLibraries: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    // Back from the PIN page returns to the profile list, not the provider picker.
    val back = if (state is PlexSignInViewModel.UiState.EnterPin) onCancelPin else onBack

    FormPage(modifier = modifier, onBack = back) {
        when (state) {
            is PlexSignInViewModel.UiState.Idle,
            is PlexSignInViewModel.UiState.CreatingPin,
            -> LoadingBody(stringResource(R.string.plex_link_title))

            is PlexSignInViewModel.UiState.WaitingForLink ->
                LinkBody(code = state.code, linkUrl = state.linkUrl)

            is PlexSignInViewModel.UiState.PickUser ->
                UsersBody(state.users, onSelectUser)

            is PlexSignInViewModel.UiState.EnterPin ->
                PinBody(state, onSubmitPin)

            is PlexSignInViewModel.UiState.SwitchingUser ->
                LoadingBody(stringResource(R.string.plex_switching_user))

            is PlexSignInViewModel.UiState.FetchingServers ->
                LoadingBody(stringResource(R.string.plex_pick_server_title))

            is PlexSignInViewModel.UiState.PickServer ->
                ServersBody(state.servers, onSelectServer)

            is PlexSignInViewModel.UiState.PickConnection ->
                ConnectionsBody(state, onSelectConnection)

            is PlexSignInViewModel.UiState.PickLibraries ->
                LibrariesBody(state, onToggleLibrary, onConfirmLibraries)

            is PlexSignInViewModel.UiState.Validating ->
                LoadingBody(stringResource(R.string.plex_validating))

            is PlexSignInViewModel.UiState.Error -> ErrorBody(state.kind, onRetry)

            // The flow host navigates away on Done; render nothing.
            is PlexSignInViewModel.UiState.Done -> Unit
        }
    }
}

@Composable
private fun LoadingBody(title: String) {
    FormTitle(title)
    CircularProgressIndicator()
}

@Composable
private fun LinkBody(code: String, linkUrl: String) {
    val dimens = LocalFormDimens.current
    FormTitle(stringResource(R.string.plex_link_title))
    Text(
        text = code.toCharArray().joinToString(" "),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    QrLinkSection(
        url = linkUrl,
        title = stringResource(R.string.plex_link_qr),
        hint = stringResource(R.string.plex_link_instruction),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing / 2),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.plex_link_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsersBody(users: List<PlexHomeUser>, onSelect: (PlexHomeUser) -> Unit) {
    FormTitle(stringResource(R.string.plex_pick_user_title))
    FormSubtitle(stringResource(R.string.plex_pick_user_subtitle))
    users.forEach { user ->
        SelectionCard(onClick = { onSelect(user) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = user.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (user.admin) {
                    PassiveBadge(stringResource(R.string.plex_user_admin_badge))
                }
                if (user.pinProtected) {
                    PassiveBadge(stringResource(R.string.plex_user_pin_badge))
                }
            }
        }
    }
}

@Composable
private fun PinBody(state: PlexSignInViewModel.UiState.EnterPin, onSubmit: (String) -> Unit) {
    // Keyed on the state so a rejected attempt starts from an empty field.
    var pin by remember(state) { mutableStateOf("") }
    FormTitle(stringResource(R.string.plex_pin_title))
    FormSubtitle(stringResource(R.string.plex_pin_instruction, state.user.title))
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit) },
        label = { Text(stringResource(R.string.plex_pin_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.wrongPin) {
        Text(
            text = stringResource(R.string.plex_pin_wrong),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    FormButton(
        text = stringResource(R.string.signin_continue),
        onClick = { onSubmit(pin) },
        enabled = pin.isNotEmpty(),
    )
}

@Composable
private fun ServersBody(servers: List<PlexResource>, onSelect: (PlexResource) -> Unit) {
    FormTitle(stringResource(R.string.plex_pick_server_title))
    servers.forEach { server ->
        SelectionCard(onClick = { onSelect(server) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!server.owned) {
                    PassiveBadge(stringResource(R.string.plex_server_shared_badge))
                }
            }
        }
    }
}

@Composable
private fun ConnectionsBody(
    state: PlexSignInViewModel.UiState.PickConnection,
    onSelect: (PlexResource, PlexConnection) -> Unit,
) {
    FormTitle(stringResource(R.string.plex_pick_connection_title))
    FormSubtitle(state.server.name)
    if (state.probing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.plex_connection_probing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.probes.forEach { probe ->
        val reachable = probe.latencyMs != null
        SelectionCard(
            onClick = { onSelect(state.server, probe.connection) },
            enabled = reachable,
        ) {
            Column {
                // Headline is the connection kind, not the plex.direct URI —
                // that hostname is an implementation detail nobody recognises.
                Text(
                    text = stringResource(
                        when {
                            probe.connection.relay -> R.string.plex_connection_relay
                            probe.connection.local -> R.string.plex_connection_local
                            else -> R.string.plex_connection_remote
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val latency = probe.latencyMs
                val latencyText = when {
                    latency != null ->
                        stringResource(R.string.plex_connection_latency, latency)
                    else -> stringResource(R.string.plex_connection_unreachable)
                }
                val address = probe.connection.address
                Text(
                    text = if (address.isBlank()) latencyText else "$address · $latencyText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (probe.connection.relay) {
                    Text(
                        text = stringResource(R.string.plex_connection_relay_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrariesBody(
    state: PlexSignInViewModel.UiState.PickLibraries,
    onToggle: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    FormTitle(stringResource(R.string.signin_pick_libraries_title))
    FormSubtitle(state.server.name)
    Text(
        text = stringResource(R.string.signin_pick_libraries_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LibraryChecklist(
        libraries = state.libraries,
        selectedIds = state.selected,
        onToggle = onToggle,
    )
    FormButton(
        text = stringResource(R.string.signin_continue),
        onClick = onConfirm,
        enabled = state.selected.isNotEmpty(),
    )
}

@Composable
private fun ErrorBody(kind: PlexSignInViewModel.ErrorKind, onRetry: () -> Unit) {
    FormTitle(stringResource(R.string.signin_provider_plex))
    Text(
        text = stringResource(
            when (kind) {
                PlexSignInViewModel.ErrorKind.LINK_FAILED -> R.string.plex_error_link_failed
                PlexSignInViewModel.ErrorKind.LINK_EXPIRED -> R.string.signin_error_code_expired
                PlexSignInViewModel.ErrorKind.SWITCH_FAILED -> R.string.plex_error_switch_failed
                PlexSignInViewModel.ErrorKind.NO_SERVERS -> R.string.plex_error_no_servers
                PlexSignInViewModel.ErrorKind.NO_MUSIC_LIBRARY ->
                    R.string.plex_error_no_music_library
                PlexSignInViewModel.ErrorKind.VALIDATION_FAILED -> R.string.plex_error_validation
            },
        ),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
    FormButton(text = stringResource(R.string.signin_retry), onClick = onRetry)
}

@Composable
private fun SelectionCard(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dimens = LocalFormDimens.current
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlMinHeight),
    ) {
        Box(modifier = Modifier.padding(dimens.itemSpacing)) {
            content()
        }
    }
}

/**
 * A non-interactive label. Deliberately not a disabled AssistChip: disabled
 * alpha would cut the contrast, which matters on an AAOS display.
 */
@Composable
private fun PassiveBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
