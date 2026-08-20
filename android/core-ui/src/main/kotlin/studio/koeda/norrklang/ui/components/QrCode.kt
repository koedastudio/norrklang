package studio.koeda.norrklang.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [content], black-on-white with a baked-in quiet zone so it
 * scans against any surrounding surface color. Rendered nearest-neighbor —
 * a blurred module edge is what breaks scans off a glossy car screen.
 *
 * Null on encode failure (content too long for a QR); callers hide the image.
 */
@Composable
fun QrCode(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
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
