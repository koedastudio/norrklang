package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Check
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Outcome
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Reason
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Report
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Live result of [studio.koeda.norrklang.data.diagnostics.ConnectionTest]:
 * lets a user see for themselves whether a slow start is the server and
 * whether a silent station is the station, before reporting either.
 */
@Composable
internal fun ConnectionTestPage(
    report: Report,
    onRunAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    SettingsPageScaffold(
        title = stringResource(R.string.settings_connection_test),
        onBack = onBack,
        modifier = modifier,
        hint = stringResource(R.string.settings_connection_test_page_hint),
    ) {
        Check.entries.forEach { check ->
            ResultRow(
                label = stringResource(
                    when (check) {
                        Check.SERVER -> R.string.settings_connection_test_server
                        Check.LIBRARIES -> R.string.settings_connection_test_libraries
                        Check.ARTISTS -> R.string.settings_connection_test_artists
                        Check.PLAYLISTS -> R.string.settings_connection_test_playlists
                        Check.STATIONS -> R.string.settings_connection_test_stations
                    },
                ),
                outcome = report.checks[check],
                finished = report.finished,
            )
            HorizontalDivider()
        }
        if (report.stations.isNotEmpty()) {
            SectionHeader(stringResource(R.string.settings_connection_test_stations))
            report.stations.forEach { station ->
                ResultRow(label = station.name, outcome = station.outcome, finished = report.finished)
                HorizontalDivider()
            }
        }
        if (report.finished) {
            Spacer(Modifier.height(dimens.itemSpacing * 2))
            SettingsActionButton(
                text = stringResource(R.string.settings_connection_test_again),
                onClick = onRunAgain,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
    }
}

/** One check and its result; a null [outcome] is still running, or skipped once [finished]. */
@Composable
private fun ResultRow(label: String, outcome: Outcome?, finished: Boolean) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Merged, so a screen reader reads label and result as one item.
            .semantics(mergeDescendants = true) {}
            .padding(vertical = dimens.itemSpacing),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (outcome) {
                null -> stringResource(
                    if (finished) {
                        R.string.settings_connection_test_skipped
                    } else {
                        R.string.settings_connection_test_checking
                    },
                )
                is Outcome.Ok -> when (val count = outcome.count) {
                    null -> stringResource(R.string.settings_connection_test_ok, outcome.millis)
                    else -> stringResource(R.string.settings_connection_test_count, count, outcome.millis)
                }
                is Outcome.Failed -> when (outcome.reason) {
                    Reason.SIGN_IN -> stringResource(R.string.settings_connection_test_sign_in)
                    Reason.HOST_NOT_FOUND -> stringResource(R.string.settings_connection_test_host_not_found)
                    Reason.REFUSED -> stringResource(R.string.settings_connection_test_refused)
                    Reason.TIMEOUT -> stringResource(R.string.settings_connection_test_timeout)
                    Reason.TLS -> stringResource(R.string.settings_connection_test_tls)
                    Reason.HTTP_STATUS ->
                        stringResource(R.string.settings_connection_test_http, outcome.httpStatus ?: 0)
                    Reason.OTHER -> stringResource(R.string.settings_connection_test_failed)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome is Outcome.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = dimens.itemSpacing),
        )
    }
}
