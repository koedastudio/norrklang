package studio.koeda.norrklang.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import studio.koeda.norrklang.ui.theme.LocalFormDimens

/**
 * The one QR hand-off layout: code left, [title] and [hint] right, with an
 * optional [note] under the hint. The URL lives in the QR only — cars have
 * no browser to tap through to. The code is hidden if [url] won't encode.
 */
@Composable
fun QrLinkSection(
    url: String,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val dimens = LocalFormDimens.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
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
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A QR code for [content], black-on-white with a baked-in quiet zone so it
 * scans against any surrounding surface color. Rendered nearest-neighbor —
 * a blurred module edge is what breaks scans off a glossy car screen.
 *
 * Null on encode failure (content too long for a QR); callers hide the image.
 */
@Composable
private fun QrCode(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { encode(content) } ?: return
    Image(
        painter = BitmapPainter(bitmap, filterQuality = FilterQuality.None),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

private const val QUIET_ZONE_MODULES = 3

private fun encode(content: String): ImageBitmap? = runCatching {
    // Error correction L maximizes capacity; the payload is compressed data,
    // so a misread would fail inflate loudly rather than corrupt quietly.
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        /* width = */ 0,
        /* height = */ 0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        ),
    )
    // One pixel per module; the Image above scales it up without smoothing.
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        val black = matrix.get(i % matrix.width, i / matrix.width)
        if (black) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}.getOrNull()
