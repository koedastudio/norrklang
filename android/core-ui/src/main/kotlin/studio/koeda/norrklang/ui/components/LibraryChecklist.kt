package studio.koeda.norrklang.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * One checkbox row. The whole row toggles: one large car-touchscreen hit
 * target and a single accessibility node; the checkbox itself is decorative.
 */
@Composable
fun CheckboxRow(
    name: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onToggle,
                role = Role.Checkbox,
            )
            .heightIn(min = dimens.controlMinHeight)
            .padding(vertical = dimens.itemSpacing / 2)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        // null: the row owns the toggle semantics and the click handling.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Multi-select over the server's music libraries. The last selected row is
 * disabled so at least one library always stays selected. A plain Column:
 * few rows, and the sign-in pages already scroll.
 */
@Composable
fun LibraryChecklist(
    libraries: List<MusicLibrary>,
    selectedIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val dimens = LocalFormDimens.current
    Column(modifier = modifier.fillMaxWidth()) {
        libraries.forEach { library ->
            val checked = library.id in selectedIds
            CheckboxRow(
                name = library.name,
                checked = checked,
                enabled = !(checked && selectedIds.size == 1),
                onToggle = { onToggle(library.id, it) },
            )
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimens.itemSpacing / 2),
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
