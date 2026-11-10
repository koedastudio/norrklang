package studio.koeda.norrklang.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.koeda.norrklang.ui.components.BackButton
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * The sign-in pages' frame: a centred, scrolling column capped at the form
 * width, with the back affordance pinned top-left when [onBack] is given.
 * Parked-only on AAOS; the pages themselves say nothing about distraction.
 */
@Composable
internal fun FormPage(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalFormDimens.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            BackButton(
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = dimens.maxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
            content = content,
        )
    }
}

@Composable
internal fun FormTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun FormSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The form's full-width primary action; [busy] swaps the label for a spinner and disables it. */
@Composable
internal fun FormButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val dimens = LocalFormDimens.current
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlMinHeight),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The form's full-width secondary action, same footprint as [FormButton]. */
@Composable
internal fun FormSecondaryButton(text: String, onClick: () -> Unit) {
    val dimens = LocalFormDimens.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlMinHeight),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
