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

/**
 * Website pointer. Deliberately says nothing about tips or sponsorship:
 * Play's payments policy bars in-app calls to action towards outside payment
 * methods, so the ask lives on norrklang.app only.
 */
@Composable
internal fun AboutPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalFormDimens.current
    SettingsPageScaffold(
        title = stringResource(R.string.settings_about),
        onBack = onBack,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(dimens.itemSpacing))
        QrLinkSection(
            url = WEBSITE_URL,
            title = stringResource(R.string.settings_about_qr_title),
            hint = stringResource(R.string.settings_about_qr_hint),
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()
        Spacer(Modifier.height(dimens.itemSpacing))
        Text(
            text = stringResource(R.string.settings_about_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = GITHUB_URL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))
        VersionFooter()
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}
