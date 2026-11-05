package studio.koeda.norrklang.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.koeda.norrklang.data.settings.StreamQuality
import studio.koeda.norrklang.ui.R

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
    SettingsPageScaffold(
        title = stringResource(R.string.settings_quality),
        onBack = onBack,
        modifier = modifier,
    ) {
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
    OptionSection(title = title) {
        StreamQuality.entries.forEach { quality ->
            OptionRow(
                label = stringResource(quality.labelRes()),
                hint = stringResource(
                    if (quality == StreamQuality.ORIGINAL) {
                        R.string.settings_quality_original_hint
                    } else {
                        R.string.settings_quality_capped_hint
                    },
                ),
                selected = quality == selected,
                onSelected = { onSelected(quality) },
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
