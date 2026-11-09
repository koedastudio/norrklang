package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.QrLinkSection
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/** Hand-off to the published policy; the URL stays visible as text for cars that can't scan. */
@Composable
internal fun PrivacyPolicyPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalFormDimens.current
    SettingsPageScaffold(
        title = stringResource(R.string.settings_privacy_policy),
        onBack = onBack,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(dimens.itemSpacing))
        QrLinkSection(
            url = PRIVACY_POLICY_URL,
            title = stringResource(R.string.settings_privacy_qr_title),
            hint = stringResource(R.string.settings_privacy_qr_hint),
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()
        Spacer(Modifier.height(dimens.itemSpacing))
        Text(
            text = PRIVACY_POLICY_URL,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}
