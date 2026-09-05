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
) {

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
     * Stream URL for a track, at the original quality or capped at [maxKbps].
     *
     * Original requests `format=raw` to disable server-side transcoding:
     * ffmpeg output lacks gapless metadata (LAME/iTunSMPB tags), so every
     * track boundary clicks or gaps, while the original file keeps its tags
     * and plays gaplessly. Capped pins `format=mp3`: a live transcode has no
     * length or seek table, and CBR MP3 is the one format the player can
     * still seek by byte estimate (see the media service's extractor flags) —
     * a server-chosen Opus/OGG stream would freeze the car's scrubber.
     */
    fun streamUrl(trackId: String, maxKbps: Int? = null): String =
        if (maxKbps == null) {
            url("stream", "id" to trackId, "format" to "raw")
        } else {
            url(
                "stream",
                "id" to trackId,
                "format" to "mp3",
                "maxBitRate" to maxKbps.toString(),
            )
        }

    fun coverArtUrl(coverArtId: String, size: Int = DEFAULT_ART_SIZE): String =
        url("getCoverArt", "id" to coverArtId, "size" to size.toString())

    private fun urlEncode(value: String): String =
        // The (String, String) overload: the Charset one is API 33+ on
        // Android and throws NoSuchMethodError on every older car.
        URLEncoder.encode(value, "UTF-8")

    companion object {
        const val DEFAULT_ART_SIZE = 512
    }
}
