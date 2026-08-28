package studio.koeda.norrklang.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/** Hand-rolled page state, same pattern as SettingsScreen's SettingsPage. */
private enum class SignInPage { ProviderPicker, SubsonicForm, Plex, JellyfinForm }

/**
 * The full sign-in flow: provider picker → Navidrome/Subsonic form, the
 * Plex link flow, or the Jellyfin form. Shared by both apps; [onBack] (when
 * non-null) renders the explicit back affordance the picker page needs on
 * AAOS, and sub-pages always offer back-to-picker.
 */
@Composable
fun SignInFlow(
    subsonicViewModel: SignInViewModel,
    plexViewModel: PlexSignInViewModel,
    jellyfinViewModel: JellyfinSignInViewModel,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableStateOf(SignInPage.ProviderPicker) }

    LaunchedEffect(plexViewModel.state) {
        if (plexViewModel.state is PlexSignInViewModel.UiState.Done) onSignedIn()
    }

    LaunchedEffect(jellyfinViewModel.state) {
        if (jellyfinViewModel.state is SignInViewModel.UiState.Done) onSignedIn()
    }

    // start() from an effect, not the picker's click handler: after process
    // death the saved page restores to Plex with a fresh (Idle) ViewModel,
    // and only an effect re-enters the link flow instead of stranding the
    // user on a spinner. start() ignores states already in flight.
    LaunchedEffect(page) {
        if (page == SignInPage.Plex) plexViewModel.start()
    }

    when (page) {
        SignInPage.ProviderPicker -> ProviderPickerPage(
            onPickSubsonic = { page = SignInPage.SubsonicForm },
            onPickPlex = { page = SignInPage.Plex },
            onPickJellyfin = { page = SignInPage.JellyfinForm },
            modifier = modifier,
            onBack = onBack,
        )

        SignInPage.SubsonicForm -> SignInScreen(
            viewModel = subsonicViewModel,
            onSignedIn = onSignedIn,
            modifier = modifier,
            onBack = { page = SignInPage.ProviderPicker },
        )

        SignInPage.Plex -> PlexSignInPage(
            state = plexViewModel.state,
            onSelectServer = plexViewModel::selectServer,
            onSelectConnection = plexViewModel::selectConnection,
            onRetry = plexViewModel::retry,
            modifier = modifier,
            onBack = {
                plexViewModel.cancel()
                page = SignInPage.ProviderPicker
            },
        )

        SignInPage.JellyfinForm -> SignInScreen(
            serverUrl = jellyfinViewModel.serverUrl,
            username = jellyfinViewModel.username,
            password = jellyfinViewModel.password,
            state = jellyfinViewModel.state,
            onServerUrlChange = jellyfinViewModel::onServerUrlChange,
            onUsernameChange = jellyfinViewModel::onUsernameChange,
            onPasswordChange = jellyfinViewModel::onPasswordChange,
            onConnect = jellyfinViewModel::connect,
            modifier = modifier,
            onBack = { page = SignInPage.ProviderPicker },
            subtitle = stringResource(R.string.signin_subtitle_jellyfin),
        )
    }
}

@Composable
private fun ProviderPickerPage(
    onPickSubsonic: () -> Unit,
    onPickPlex: () -> Unit,
    onPickJellyfin: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val dimens = LocalFormDimens.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            BackButton(
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = dimens.maxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
        ) {
            Text(
                text = stringResource(R.string.signin_choose_provider_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.signin_choose_provider_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderCard(
                icon = Icons.Filled.Dns,
                title = stringResource(R.string.signin_provider_subsonic),
                description = stringResource(R.string.signin_provider_subsonic_desc),
                onClick = onPickSubsonic,
            )
            ProviderCard(
                icon = Icons.Filled.PlayCircleFilled,
                title = stringResource(R.string.signin_provider_plex),
                description = stringResource(R.string.signin_provider_plex_desc),
                onClick = onPickPlex,
            )
            ProviderCard(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.signin_provider_jellyfin),
                description = stringResource(R.string.signin_provider_jellyfin_desc),
                onClick = onPickJellyfin,
            )
        }
    }
}

@Composable
private fun ProviderCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val dimens = LocalFormDimens.current
    OutlinedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlMinHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
            modifier = Modifier.padding(dimens.itemSpacing),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
