package studio.koeda.norrklang.media

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A radio station's logo from its homepage, for stations the server has no
 * image for: the page's apple-touch-icon, else its Open Graph image, else a
 * PNG/JPEG/WebP favicon of usable size. Only the station's own site is
 * contacted — never a third-party icon service.
 */
internal object StationLogo {

    /** Downloads the best icon [pageUrl] advertises into [target]; false when there is none. */
    fun fetch(pageUrl: String, target: File): Boolean {
        val html = try {
            readPage(pageUrl) ?: return false
        } catch (_: IOException) {
            return false
        }
        val iconUrl = pickIconUrl(html, pageUrl) ?: return false
        return try {
            downloadImage(iconUrl, target)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** The best icon URL advertised by [html] at [pageUrl], resolved; null when none qualifies. */
    fun pickIconUrl(html: String, pageUrl: String): String? {
        val base = runCatching { URL(pageUrl) }.getOrNull() ?: return null
        val candidates = mutableListOf<Candidate>()
        for (tag in TAG.findAll(html)) {
            val attrs = attributes(tag.value)
            val candidate = when (tag.groupValues[1].lowercase()) {
                "link" -> linkCandidate(attrs)
                "meta" -> metaCandidate(attrs)
                else -> null
            } ?: continue
            val resolved = runCatching { URL(base, candidate.url.trim()).toString() }.getOrNull() ?: continue
            if (!resolved.startsWith("http")) continue
            val path = resolved.substringBefore('?').substringBefore('#').lowercase()
            // BitmapFactory decodes neither ICO nor SVG.
            if (UNDECODABLE.any(path::endsWith)) continue
            candidates += candidate.copy(url = resolved)
        }
        return candidates
            .filter { it.size >= MIN_ICON_SIZE }
            .sortedWith(compareBy<Candidate> { it.rank }.thenByDescending { it.size })
            .firstOrNull()
            ?.url
    }

    private data class Candidate(val url: String, val rank: Int, val size: Int)

    private fun linkCandidate(attrs: Map<String, String>): Candidate? {
        val rel = attrs["rel"]?.lowercase()?.split(WHITESPACE).orEmpty()
        val href = attrs["href"] ?: return null
        val declared = parseSize(attrs["sizes"])
        return when {
            "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel ->
                Candidate(href, RANK_TOUCH_ICON, declared ?: DEFAULT_TOUCH_ICON_SIZE)
            "icon" in rel -> Candidate(href, RANK_FAVICON, declared ?: UNKNOWN_FAVICON_SIZE)
            else -> null
        }
    }

    private fun metaCandidate(attrs: Map<String, String>): Candidate? {
        val key = (attrs["property"] ?: attrs["name"])?.lowercase()
        if (key != "og:image" && key != "og:image:url" && key != "og:image:secure_url") return null
        val content = attrs["content"] ?: return null
        return Candidate(content, RANK_OG_IMAGE, DEFAULT_OG_IMAGE_SIZE)
    }

    private fun attributes(tag: String): Map<String, String> =
        ATTRIBUTE.findAll(tag).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[3] ?: m.groups[4] ?: m.groups[5])!!.value
        }

    /** The smaller edge of the first `WxH` in a `sizes` attribute; null for absent or `any`. */
    private fun parseSize(sizes: String?): Int? =
        sizes?.let { SIZE.find(it) }?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    private fun readPage(pageUrl: String): String? {
        val connection = open(pageUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (connection.contentType?.startsWith("text/html") != true) return null
            val charset = connection.contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { charset(it) }.getOrNull() }
                ?: Charsets.UTF_8
            val bytes = connection.inputStream.use { readBounded(it, MAX_PAGE_BYTES) }
            return String(bytes, charset)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadImage(url: String, target: File) {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.contentType?.startsWith("image/") != true
            ) {
                throw IOException("No image at station icon URL")
            }
            val tmp = File.createTempFile("logo-", ".part", target.parentFile)
            try {
                val bytes = connection.inputStream.use { readBounded(it, MAX_IMAGE_BYTES) }
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) throw IOException("Could not store station logo")
            } finally {
                tmp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Some station sites answer the default Java agent with an error page.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,image/*")
        }

    /** Reads at most [limit] bytes; the page is truncated, an image rejected. */
    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) {
                if (limit == MAX_IMAGE_BYTES) throw IOException("Station logo exceeds size limit")
                out.write(buffer, 0, (limit - out.size()).toInt())
                return out.toByteArray()
            }
            out.write(buffer, 0, read)
        }
    }

    private val TAG = Regex("<(link|meta)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([a-zA-Z:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val SIZE = Regex("(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val UNDECODABLE = listOf(".ico", ".svg")

    private const val RANK_TOUCH_ICON = 0
    private const val RANK_OG_IMAGE = 1
    private const val RANK_FAVICON = 2

    // Assumed edges when a tag declares none: the platform's touch-icon
    // default, a typical share image, and a favicon too small to trust.
    private const val DEFAULT_TOUCH_ICON_SIZE = 180
    private const val DEFAULT_OG_IMAGE_SIZE = 600
    private const val UNKNOWN_FAVICON_SIZE = 0

    /** Below this a logo just blurs on a car tile — the glyph looks better. */
    const val MIN_ICON_SIZE = 96

    private const val MAX_PAGE_BYTES = 512L * 1024
    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val USER_AGENT = "Mozilla/5.0 (compatible; Norrklang)"
}
