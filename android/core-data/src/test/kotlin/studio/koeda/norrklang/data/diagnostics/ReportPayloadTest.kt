package studio.koeda.norrklang.data.diagnostics

import java.util.Base64
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportPayloadTest {

    private val metadata = ReportMetadata(appVersion = "1.0.1", apiLevel = 32, device = "Polestar PS4")

    /** Decodes a report URL the way www/src/pages/report.astro does. */
    private fun decode(url: String): String {
        val fragment = url.substringAfter('#')
        assertEquals(ReportPayload.FORMAT_VERSION, fragment.substringBefore('.'))
        val bytes = Base64.getUrlDecoder().decode(fragment.substringAfter('.'))
        val inflater = Inflater(/* nowrap = */ true)
        inflater.setInput(bytes)
        val buffer = ByteArray(64 * 1024)
        val length = inflater.inflate(buffer)
        assertTrue(inflater.finished(), "payload should inflate in one pass")
        inflater.end()
        return String(buffer, 0, length, Charsets.UTF_8)
    }

    @Test
    fun `round-trips metadata, crash and events`() {
        val url = ReportPayload.buildUrl(
            metadata,
            crash = "2026-08-20 10:00 crash on thread main\njava.lang.NoSuchMethodError: URLEncoder.encode",
            events = listOf("10:01 browse — ServerError: boom", "10:00 keystore-decrypt — ProviderException"),
        )!!
        assertTrue(url.startsWith("${ReportPayload.REPORT_URL}#${ReportPayload.FORMAT_VERSION}."))
        val text = decode(url)
        assertContains(text, "Norrklang 1.0.1 · API 32 · Polestar PS4")
        assertContains(text, "== crash ==")
        assertContains(text, "NoSuchMethodError")
        assertContains(text, "== errors ==")
        assertContains(text, "ServerError: boom")
    }

    @Test
    fun `nothing to report yields null`() {
        assertNull(ReportPayload.buildUrl(metadata, crash = null, events = emptyList()))
        assertNull(ReportPayload.buildUrl(metadata, crash = "  ", events = emptyList()))
    }

    @Test
    fun `oversized input is trimmed under the URL cap, newest events kept`() {
        val crash = (1..200).joinToString("\n") { "at frame$it.method$it(Source$it.kt:$it)" }
        // Random hex resists deflate, forcing real trimming.
        val random = kotlin.random.Random(42)
        val events = (1..200).map { n ->
            "event$n " + (1..60).joinToString("") { random.nextInt(16).toString(16) }
        }
        val url = ReportPayload.buildUrl(metadata, crash, events)!!
        assertTrue(url.length <= ReportPayload.MAX_URL_LENGTH, "url was ${url.length} chars")
        val text = decode(url)
        assertContains(text, "event1 ", message = "newest event must survive trimming")
        assertContains(text, "Norrklang 1.0.1")
    }

    @Test
    fun `fragment survives url-safe encoding`() {
        // Payload bytes must never produce '+', '/' or '=' in the fragment.
        val url = ReportPayload.buildUrl(metadata, crash = "x".repeat(500), events = emptyList())!!
        val fragment = url.substringAfter('#').substringAfter('.')
        assertTrue(fragment.none { it == '+' || it == '/' || it == '=' }, "fragment must be unpadded base64url")
    }
}
