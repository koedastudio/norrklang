package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.components.LibraryChecklist
import studio.koeda.norrklang.ui.settings.SettingsViewModel.PickerState
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Which of the server's music libraries to show, as a sub-page of
 * [SettingsScreen]. [onToggle] receives the library id and whether it is
 * now selected; the store keeps the excluded set (see LibraryScope).
 */
@Composable
internal fun LibrariesPage(
    state: PickerState,
    excludedIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = stringResource(R.string.settings_libraries),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing / 2))
        Text(
            text = stringResource(R.string.settings_libraries_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(dimens.itemSpacing))

        when (state) {
            PickerState.Loading -> CenteredBox { CircularProgressIndicator() }
            PickerState.Error -> CenteredBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.settings_picker_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(dimens.itemSpacing))
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.settings_picker_retry))
                    }
                }
            }
            is PickerState.Loaded -> if (state.items.isEmpty()) {
                CenteredBox {
                    Text(
                        text = stringResource(R.string.settings_picker_no_libraries),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val libraries = remember(state.items) {
                    state.items.map { MusicLibrary(it.id, it.name) }
                }
                val selectedIds = remember(state.items, excludedIds) {
                    libraries.map { it.id }.filterNot { it in excludedIds }.toSet()
                        // Everything excluded reads as everything selected (see LibraryScope).
                        .ifEmpty { libraries.map { it.id }.toSet() }
                }
                LibraryChecklist(
                    libraries = libraries,
                    selectedIds = selectedIds,
                    onToggle = onToggle,
                    note = stringResource(R.string.settings_libraries_playlists_note),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
