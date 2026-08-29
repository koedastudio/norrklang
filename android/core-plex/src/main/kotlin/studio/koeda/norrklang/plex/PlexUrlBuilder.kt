package studio.koeda.norrklang.plex

import java.net.URLEncoder

/**
 * Builds fully-authenticated URLs for the binary endpoints (stream parts,
 * transcoded streams, artwork). Plex accepts the token as a query parameter,
 * so a complete URL is all ExoPlayer or an image loader needs — same model as
 * the Subsonic side.
 *
 * SECURITY: these URLs embed the auth token — never log them.
 */
class PlexUrlBuilder(
    private val baseUrl: String,
    private val token: String,
    private val clientInfo: PlexClientInfo,
) {

    /**
     * Stream URL for a track, at the original quality or capped at [maxKbps].
     * Original direct-plays the media part; capped goes through the server's
     * universal transcoder ([transcodeUrl]).
     */
    fun streamUrl(ratingKey: String, partKey: String, maxKbps: Int? = null): String =
        if (maxKbps == null) partUrl(partKey) else transcodeUrl(ratingKey, maxKbps)

    /**
     * Direct-play URL for a media part. [partKey] is the server-relative
     * path from [studio.koeda.norrklang.plex.model.PlexPart.key]
     * (e.g. `/library/parts/123/456/file.flac`) — the original file,
     * no transcoding.
     */
    fun partUrl(partKey: String): String =
        "$baseUrl$partKey?X-Plex-Token=${urlEncode(token)}"

    /**
     * Server-transcoded MP3 stream capped at [maxKbps], via the universal
     * transcode endpoint (what Plexamp uses for its bandwidth-limited
     * tiers). The `session` id is unique per track: PMS kills an existing
     * transcode when a new one starts under the same session, which would
     * make the gapless preloader cancel the currently playing track.
     */
    private fun transcodeUrl(ratingKey: String, maxKbps: Int): String =
        "$baseUrl/music/:/transcode/universal/start.mp3" +
            "?path=${urlEncode("/library/metadata/$ratingKey")}" +
            "&mediaIndex=0&partIndex=0" +
            "&protocol=http" +
            "&directPlay=0&directStream=0" +
            "&musicBitrate=$maxKbps" +
            "&session=${urlEncode("${clientInfo.clientId}-$ratingKey")}" +
            "&X-Plex-Token=${urlEncode(token)}" +
            "&X-Plex-Client-Identifier=${urlEncode(clientInfo.clientId)}" +
            "&X-Plex-Product=${urlEncode(PlexClientInfo.PRODUCT)}" +
            "&X-Plex-Platform=${urlEncode(PlexClientInfo.PLATFORM)}" +
            "&X-Plex-Device=${urlEncode(PlexClientInfo.DEVICE)}"

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
