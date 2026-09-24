package studio.koeda.norrklang.data.diagnostics

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * Encodes text into a URL fragment for the QR hand-offs on the settings
 * screen: `<pageUrl>#<version>.<base64url(rawDeflate(text))>`.
 *
 * The car transmits nothing — the QR is scanned by the user's phone, and the
 * payload rides in the URL *fragment*, which browsers never send to the
 * server. The landing pages under www/src/pages decode it client-side; both
 * sides must agree on the version prefix, raw deflate ("deflate-raw" in the
 * page) and unpadded base64url.
 */
object FragmentPayload {

    /** Comfortable scan-off-a-screen ceiling; QR hard limit is ~2950 bytes. */
    const val MAX_URL_LENGTH = 1500

    fun url(pageUrl: String, version: String, text: String): String =
        "$pageUrl#$version." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(deflateRaw(text))

    // Raw deflate (nowrap) to match the page's DecompressionStream("deflate-raw").
    private fun deflateRaw(text: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ true)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val buffer = ByteArray(1024)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            out.write(buffer, 0, written)
        }
        deflater.end()
        return out.toByteArray()
    }
}
