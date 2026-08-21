package studio.koeda.norrklang.plex

import java.net.URLEncoder

/**
 * Builds fully-authenticated URLs for the binary endpoints (stream parts,
 * artwork). Plex accepts the token as a query parameter, so a complete URL is
 * all ExoPlayer or an image loader needs — same model as the Subsonic side.
 *
 * SECURITY: these URLs embed the auth token — never log them.
 */
class PlexUrlBuilder(
    private val baseUrl: String,
    private val token: String,
) {

    /**
     * Direct-play URL for a media part. [partKey] is the server-relative
     * path from [studio.koeda.norrklang.plex.model.PlexPart.key]
     * (e.g. `/library/parts/123/456/file.flac`) — the original file,
     * no transcoding.
     */
    fun partUrl(partKey: String): String =
        "$baseUrl$partKey?X-Plex-Token=${urlEncode(token)}"

    /**
     * Server-side scaled artwork for a thumb path handed out in metadata
     * (e.g. `/library/metadata/123/thumb/456`).
     */
    fun artworkUrl(thumbPath: String, size: Int = DEFAULT_ART_SIZE): String =
        "$baseUrl/photo/:/transcode" +
            "?width=$size&height=$size&minSize=1&upscale=1" +
            "&url=${urlEncode(thumbPath)}" +
            "&X-Plex-Token=${urlEncode(token)}"

    private fun urlEncode(value: String): String =
        // The (String, String) overload: the Charset one is API 33+ on Android
        // and throws NoSuchMethodError on every older car. This module is pure
        // JVM, so neither the compiler nor Android lint will warn — same
        // incident as SubsonicUrlBuilder (fixed in 8a53195); keep it stringly.
        URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Matches the Subsonic side (SubsonicUrlBuilder.DEFAULT_ART_SIZE). */
        const val DEFAULT_ART_SIZE = 512
    }
}
