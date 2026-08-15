package studio.koeda.norrklang.data.artwork

/**
 * Contract for the in-app artwork ContentProvider.
 *
 * Car media hosts only render artwork they can open through a content
 * resolver — they refuse to download remote http(s) URLs. So every artwork
 * URI handed to the media session points at our provider, which streams (and
 * caches) the authenticated Subsonic `getCoverArt` call. This also keeps the
 * auth token out of URIs that leave the process.
 */
object ArtworkContract {

    /** Appended to the applicationId to form the provider authority. */
    const val AUTHORITY_SUFFIX = ".artwork"

    const val PATH_COVER = "cover"

    /** Composed home-tab button images (collage + overlay), rendered in-app. */
    const val PATH_HOME = "home"

    /**
     * A content URI resolving to the cover art bytes for a Subsonic coverArt
     * id. Also records the id in [KnownCoverIds] — the provider refuses to
     * fetch ids that never came through here.
     */
    fun coverUri(packageName: String, coverArtId: String): String {
        KnownCoverIds.register(coverArtId)
        return "content://$packageName$AUTHORITY_SUFFIX/$PATH_COVER/${encode(coverArtId)}"
    }

    /**
     * A content URI resolving to the generated image for a home-tab button.
     * Valid keys are fixed in the provider — no registration needed.
     */
    fun homeUri(packageName: String, key: String): String =
        "content://$packageName$AUTHORITY_SUFFIX/$PATH_HOME/${encode(key)}"

    /**
     * A content URI resolving to the generated collage for a dynamic catalog
     * mix tile (`home/<kind>/<key>`, e.g. `home/genre/Rock`). Kinds are fixed
     * in the provider; the key is validated against the current mix snapshot
     * there, so no registration is needed here either.
     */
    fun homeMixUri(packageName: String, kind: String, key: String): String =
        "content://$packageName$AUTHORITY_SUFFIX/$PATH_HOME/$kind/${encode(key)}"

    /**
     * Percent-encodes a path segment with android.net.Uri.encode's exact
     * allowed set — reimplemented here so core-data stays JVM-testable.
     */
    private fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isAllowed()) append(c) else append('%').append("%02X".format(byte))
        }
    }

    private fun Char.isAllowed(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "_-!.~'()*"
}
