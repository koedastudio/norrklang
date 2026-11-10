package studio.koeda.norrklang.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.ui.R

/**
 * Hub for the info pages, kept off the main page so that stays short.
 * Reachable signed out too: a failure that signed the user out is exactly
 * what the diagnostics log should explain.
 */
@Composable
internal fun AboutSupportPage(
    onAbout: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_about_support),
        onBack = onBack,
        modifier = modifier,
    ) {
        NavigationRow(
            title = stringResource(R.string.settings_about),
            subtitle = stringResource(R.string.settings_about_hint),
            onClick = onAbout,
        )
        HorizontalDivider()
        // Play requires an in-app link; a page with a QR, as cars have no browser.
        NavigationRow(
            title = stringResource(R.string.settings_privacy_policy),
            subtitle = PRIVACY_POLICY_URL,
            onClick = onPrivacyPolicy,
        )
        HorizontalDivider()
        NavigationRow(
            title = stringResource(R.string.settings_diagnostics),
            subtitle = stringResource(R.string.settings_diagnostics_hint),
            onClick = onDiagnostics,
        )
        HorizontalDivider()
    }
}
