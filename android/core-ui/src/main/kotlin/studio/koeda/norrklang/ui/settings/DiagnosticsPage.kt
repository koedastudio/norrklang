package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Read-only dump of [studio.koeda.norrklang.data.diagnostics.Diagnostics] —
 * the last crash and recent errors — so a user can read a failure back to us
 * from the car, where nothing else (logcat, Play vitals) is available.
 */
@Composable
internal fun DiagnosticsPage(
    text: String,
    reportUrl: String?,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    SettingsPageScaffold(
        title = stringResource(R.string.settings_diagnostics),
        onBack = onBack,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(dimens.itemSpacing))
        if (reportUrl != null) {
            // Nothing is sent from the car: the trimmed log rides in the URL
            // fragment, and norrklang.app/report offers a prefilled GitHub issue.
            QrLinkSection(
                url = reportUrl,
                title = stringResource(R.string.settings_diagnostics_report_title),
                hint = stringResource(R.string.settings_diagnostics_report_hint),
            )
            Spacer(Modifier.height(dimens.itemSpacing))
            HorizontalDivider()
            Spacer(Modifier.height(dimens.itemSpacing))
        }
        if (text.isBlank()) {
            Text(
                text = stringResource(R.string.settings_diagnostics_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(dimens.itemSpacing * 2))
            SettingsActionButton(
                text = stringResource(R.string.settings_diagnostics_clear),
                onClick = onClear,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}
