package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.ui.R
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/** Building-block rows for the settings pages. */

/** Avatar + who's signed in. */
@Composable
internal fun AccountHeader(title: String, subtitle: String) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.itemSpacing),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(dimens.controlMinHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimens.controlMinHeight / 2),
            )
        }
        Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The title-over-subtitle labels every row starts with, taking the row's remaining width. */
@Composable
private fun RowScope.RowLabels(title: String, subtitle: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One boolean setting as a full-width row with a trailing switch. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Whole row toggles: one large car-touchscreen hit target and a
            // single accessibility toggle node.
            .toggleable(value = checked, onValueChange = onToggle, role = Role.Switch)
            .padding(vertical = dimens.itemSpacing),
    ) {
        RowLabels(title, subtitle)
        // null: the row owns the toggle semantics and the click handling.
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = dimens.itemSpacing),
        )
    }
}

/** Navigation row into a settings sub-page; [trailing] sits between the labels and the chevron. */
@Composable
internal fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val dimens = LocalFormDimens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimens.itemSpacing),
    ) {
        RowLabels(title, subtitle)
        trailing()
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Navigation row into one exclusion picker, with the current count trailing. */
@Composable
internal fun ExclusionRow(
    title: String,
    subtitle: String,
    excludedCount: Int,
    onClick: () -> Unit,
) {
    NavigationRow(title = title, subtitle = subtitle, onClick = onClick) {
        if (excludedCount > 0) {
            Text(
                text = excludedCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = LocalFormDimens.current.itemSpacing),
            )
        }
    }
}

/** Navigation row into the songs hearted on radio (see SavedSongsPage). */
@Composable
internal fun SavedSongsRow(count: Int?, onClick: () -> Unit) {
    NavigationRow(
        title = stringResource(R.string.settings_saved_songs),
        subtitle = if (count == null || count == 0) {
            stringResource(R.string.settings_saved_songs_hint)
        } else {
            pluralStringResource(R.plurals.settings_saved_songs_count, count, count)
        },
        onClick = onClick,
    )
}

/** Navigation row into the website pointer (see AboutPage). */
@Composable
internal fun AboutRow(onClick: () -> Unit) {
    NavigationRow(
        title = stringResource(R.string.settings_about),
        subtitle = stringResource(R.string.settings_about_hint),
        onClick = onClick,
    )
}

/** Navigation row into the on-device error log (see DiagnosticsPage). */
@Composable
internal fun DiagnosticsRow(onClick: () -> Unit) {
    NavigationRow(
        title = stringResource(R.string.settings_diagnostics),
        subtitle = stringResource(R.string.settings_diagnostics_hint),
        onClick = onClick,
    )
}

/**
 * Play requires an in-app privacy-policy link. Opens in the device browser;
 * cars without one still show the URL text so the user can find it elsewhere.
 */
@Composable
internal fun PrivacyPolicyRow() {
    val uriHandler = LocalUriHandler.current
    NavigationRow(
        title = stringResource(R.string.settings_privacy_policy),
        subtitle = PRIVACY_POLICY_URL,
        onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } },
    )
}

/** A titled group of mutually exclusive [OptionRow]s. */
@Composable
internal fun OptionSection(title: String, options: @Composable () -> Unit) {
    val dimens = LocalFormDimens.current
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = dimens.itemSpacing),
        )
        options()
        Spacer(Modifier.height(dimens.itemSpacing / 2))
    }
}

/** One radio option in an [OptionSection]. */
@Composable
internal fun OptionRow(
    label: String,
    hint: String,
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
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A page-level action (sign out, clear): car-sized, bold, outlined unless it is the page's one primary action. */
@Composable
internal fun SettingsActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
) {
    val dimens = LocalFormDimens.current
    val sized = modifier
        .widthIn(min = 200.dp)
        .heightIn(min = dimens.controlMinHeight)
    val label: @Composable RowScope.() -> Unit = { Text(text = text, fontWeight = FontWeight.Bold) }
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = sized, content = label)
    } else {
        Button(onClick = onClick, modifier = sized, content = label)
    }
}

@Composable
internal fun VersionFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    if (version != null) {
        Text(
            text = stringResource(R.string.settings_app_version, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}
