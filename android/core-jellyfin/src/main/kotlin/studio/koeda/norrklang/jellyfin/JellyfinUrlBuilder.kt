package studio.koeda.norrklang.jellyfin

import java.net.URLEncoder

/**
 * Builds fully-authenticated URLs for the binary endpoints (audio streams,
 * artwork). Jellyfin accepts the token as an `api_key` query parameter, so a
 * complete URL is all ExoPlayer or an image loader needs — same model as the
 * Subsonic and Plex sides.
 *
 * SECURITY: these URLs embed the auth token — never log them.
 */
class JellyfinUrlBuilder(
    private val baseUrl: String,
    private val token: String,
) {

    /** Direct-play URL for an audio item — the original file, no transcoding. */
    fun streamUrl(itemId: String): String =
        "$baseUrl/Audio/${urlEncode(itemId)}/stream" +
            "?static=true&api_key=${urlEncode(token)}"

    /** Server-side scaled primary image of the item owning the artwork. */
    fun artworkUrl(itemId: String, size: Int = DEFAULT_ART_SIZE): String =
        "$baseUrl/Items/${urlEncode(itemId)}/Images/Primary" +
            "?fillWidth=$size&fillHeight=$size&quality=90" +
            "&api_key=${urlEncode(token)}"

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
