package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * The frame every settings page shares: full-bleed background, the header
 * row (a back button when [onBack] is given, then the title), an optional
 * [hint] under it, then the divider. [scroll] is false for pages whose body
 * scrolls itself (lazy lists, the library checklist).
 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    scroll: Boolean = true,
    divider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalFormDimens.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = dimens.screenPadding, vertical = dimens.itemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                BackButton(onBack)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = if (onBack != null) 8.dp else 0.dp),
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimens.itemSpacing / 2),
            )
        }
        Spacer(Modifier.height(dimens.itemSpacing))
        if (divider) {
            HorizontalDivider()
        }
        content()
    }
}
