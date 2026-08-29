package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.data.settings.StreamQuality
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Streaming-quality sub-page: one tier per network type, applied by the
 * player's resolver on the next track load (the playing track keeps its
 * stream). Capped tiers are server-transcoded, so gapless playback only
 * survives on Original.
 */
@Composable
internal fun StreamQualityPage(
    qualityWifi: StreamQuality,
    qualityCellular: StreamQuality,
    onWifiSelected: (StreamQuality) -> Unit,
    onCellularSelected: (StreamQuality) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = stringResource(R.string.settings_quality),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        HorizontalDivider()

        QualitySection(
            title = stringResource(R.string.settings_quality_wifi),
            selected = qualityWifi,
            onSelected = onWifiSelected,
        )
        HorizontalDivider()
        QualitySection(
            title = stringResource(R.string.settings_quality_cellular),
            selected = qualityCellular,
            onSelected = onCellularSelected,
        )
    }
}

@Composable
private fun QualitySection(
    title: String,
    selected: StreamQuality,
    onSelected: (StreamQuality) -> Unit,
) {
    val dimens = LocalFormDimens.current
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = dimens.itemSpacing),
        )
        StreamQuality.entries.forEach { quality ->
            QualityRow(
                quality = quality,
                selected = quality == selected,
                onSelected = { onSelected(quality) },
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing / 2))
    }
}

@Composable
private fun QualityRow(
    quality: StreamQuality,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Whole row selects: one large car-touchscreen hit target and a
            // single accessibility radio node (same rationale as
            // SettingsToggleRow).
            .selectable(selected = selected, onClick = onSelected, role = Role.RadioButton)
            .padding(vertical = dimens.itemSpacing / 2),
    ) {
        // null: the row owns the selection semantics and the click handling.
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
            Text(
                text = stringResource(quality.labelRes()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    if (quality == StreamQuality.ORIGINAL) {
                        R.string.settings_quality_original_hint
                    } else {
                        R.string.settings_quality_capped_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun StreamQuality.labelRes(): Int = when (this) {
    StreamQuality.ORIGINAL -> R.string.settings_quality_original
    StreamQuality.HIGH -> R.string.settings_quality_high
    StreamQuality.MEDIUM -> R.string.settings_quality_medium
    StreamQuality.LOW -> R.string.settings_quality_low
}
