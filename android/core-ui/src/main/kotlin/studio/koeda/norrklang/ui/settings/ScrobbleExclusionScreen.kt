package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.settings.SettingsViewModel.PickerItem
import studio.koeda.norrklang.ui.settings.SettingsViewModel.PickerState
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * Full-screen checkbox picker for one scrobble exclusion list, shown as a
 * sub-page of [SettingsScreen]. Excluded entries are pinned in a group at the
 * top so they can be un-excluded without searching.
 *
 * [searchPlaceholder] non-null adds a filter field — needed for artists
 * (hundreds of rows), not for the few dozen playlists.
 */
@Composable
internal fun ScrobbleExclusionScreen(
    title: String,
    emptyText: String,
    searchPlaceholder: String?,
    state: PickerState,
    excludedIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalFormDimens.current
    var query by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))

        if (searchPlaceholder != null && state is PickerState.Loaded) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(searchPlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(dimens.itemSpacing))
        }

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
            is PickerState.Loaded -> ItemList(
                items = state.items,
                query = query,
                emptyText = emptyText,
                excludedIds = excludedIds,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun ItemList(
    items: List<PickerItem>,
    query: String,
    emptyText: String,
    excludedIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    if (items.isEmpty()) {
        CenteredBox {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Searching shows one flat filtered list; otherwise excluded entries are
    // pinned in a titled group on top. remember: don't re-filter hundreds of
    // artist rows on every recomposition.
    val (excluded, rest) = remember(items, query, excludedIds) {
        val filtered =
            items.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        if (query.isBlank()) {
            filtered.partition { it.id in excludedIds }
        } else {
            emptyList<PickerItem>() to filtered
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (excluded.isNotEmpty()) {
            item(key = "excluded-header") {
                GroupHeader(stringResource(R.string.settings_picker_excluded_group, excluded.size))
            }
            items(excluded, key = { "excluded-" + it.id }) { item ->
                PickerRow(item, checked = true, onToggle)
            }
            item(key = "excluded-divider") {
                Spacer(Modifier.height(LocalFormDimens.current.itemSpacing))
                HorizontalDivider()
            }
        }
        items(rest, key = { it.id }) { item ->
            PickerRow(item, checked = item.id in excludedIds, onToggle)
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = LocalFormDimens.current.itemSpacing / 2),
    )
}

@Composable
private fun PickerRow(
    item: PickerItem,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            // Whole row toggles: one large car-touchscreen hit target and a
            // single accessibility node (same pattern as the settings rows).
            .toggleable(
                value = checked,
                onValueChange = { onToggle(item.id, it) },
                role = Role.Checkbox,
            )
            .heightIn(min = dimens.controlMinHeight)
            .padding(vertical = dimens.itemSpacing / 2),
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        // null: the row owns the toggle semantics and the click handling.
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
    }
}
