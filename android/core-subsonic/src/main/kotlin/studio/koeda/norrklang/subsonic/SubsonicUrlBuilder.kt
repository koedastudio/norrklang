package studio.koeda.norrklang.subsonic

import java.net.URLEncoder

/**
 * Builds fully-authenticated URLs for the binary endpoints (`stream`,
 * `getCoverArt`). Subsonic auth rides in query parameters, so a complete URL
 * is all ExoPlayer or an image loader needs. The fixed salt/token pair keeps
 * URLs stable across calls and restarts (cache-friendly); salt reuse is
 * explicitly allowed by the Subsonic scheme.
 *
 * SECURITY: these URLs embed the auth token — never log them.
 */
class SubsonicUrlBuilder(
    private val credentials: SubsonicCredentials,
    private val streamOriginal: Boolean = true,
) {

    /** Same credentials, different stream preference (builders are immutable). */
    fun withStreamOriginal(enabled: Boolean): SubsonicUrlBuilder =
        if (enabled == streamOriginal) this else SubsonicUrlBuilder(credentials, enabled)

    private fun url(endpoint: String, vararg params: Pair<String, String>): String =
        buildString {
            append(credentials.baseUrl)
            append("/rest/")
            append(endpoint)
            var separator = '?'
            for ((key, value) in credentials.authParams() + params) {
                append(separator).append(key).append('=').append(urlEncode(value))
                separator = '&'
            }
        }

    /**
     * With [streamOriginal], `format=raw` disables server-side transcoding:
     * ffmpeg output lacks gapless metadata (LAME/iTunSMPB tags), so every
     * track boundary clicks or gaps, while the original file keeps its tags
     * and plays gaplessly. Otherwise no format is sent and the server's
     * per-player transcoding config decides.
     */
    fun streamUrl(trackId: String): String =
        if (streamOriginal) {
            url("stream", "id" to trackId, "format" to "raw")
        } else {
            url("stream", "id" to trackId)
        }

    fun coverArtUrl(coverArtId: String, size: Int = DEFAULT_ART_SIZE): String =
        url("getCoverArt", "id" to coverArtId, "size" to size.toString())

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val DEFAULT_ART_SIZE = 512
    }
}
