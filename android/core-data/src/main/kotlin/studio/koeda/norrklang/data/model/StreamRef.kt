package studio.koeda.norrklang.data.model

import java.net.URLDecoder
import java.net.URLEncoder
import studio.koeda.norrklang.data.session.MusicProvider

/**
 * Canonical, quality-independent stream reference carried in
 * [Track.streamUrl] and MediaItem URIs (`norrklang-stream://…`). The player's
 * resolver turns it into a real server URL at load time, so the URL can
 * follow the current network type and quality setting — and a persisted or
 * long-lived queue never pins stale auth or quality choices.
 *
 * [plexPartKey] is Plex's server-relative media-part path, required for
 * direct play there; the other providers stream by track id alone.
 */
data class StreamRef(
    val provider: MusicProvider,
    val trackId: String,
    val plexPartKey: String? = null,
) {

    fun encode(): String = buildString {
        append(SCHEME).append("://").append(provider.name.lowercase())
        append("?id=").append(urlEncode(trackId))
        plexPartKey?.let { append("&part=").append(urlEncode(it)) }
    }

    companion object {
        const val SCHEME = "norrklang-stream"

        /** Null for any URI that is not a well-formed [StreamRef]. */
        fun parse(uri: String): StreamRef? {
            val prefix = "$SCHEME://"
            if (!uri.startsWith(prefix)) return null
            val hostAndQuery = uri.removePrefix(prefix).split('?', limit = 2)
            if (hostAndQuery.size != 2) return null
            val provider = MusicProvider.entries
                .firstOrNull { it.name.lowercase() == hostAndQuery[0] } ?: return null
            val params = hostAndQuery[1].split('&').mapNotNull { param ->
                val pair = param.split('=', limit = 2)
                if (pair.size == 2) pair[0] to urlDecode(pair[1]) else null
            }.toMap()
            val trackId = params["id"] ?: return null
            return StreamRef(provider, trackId, params["part"])
        }

        // The (String, String) overloads: the Charset ones are API 33+ on
        // Android and throw NoSuchMethodError on every older car.
        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }
}
