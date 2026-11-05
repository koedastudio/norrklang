package studio.koeda.norrklang.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import studio.koeda.norrklang.ui.components.QrCode
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * QR hand-off to the phone's camera, with the URL in the QR only — cars have
 * no browser to tap through to.
 */
@Composable
internal fun QrLinkSection(url: String, title: String, hint: String) {
    val dimens = LocalFormDimens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrCode(
            content = url,
            contentDescription = title,
            modifier = Modifier.size(dimens.controlMinHeight * 3),
        )
        Column(modifier = Modifier.padding(start = dimens.itemSpacing)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
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
