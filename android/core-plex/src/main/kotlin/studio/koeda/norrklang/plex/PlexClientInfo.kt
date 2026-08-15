package studio.koeda.norrklang.plex

/**
 * The X-Plex-* identity every API request carries (via `plexHeaders`).
 * [clientId] is the persistent X-Plex-Client-Identifier: Plex treats it as
 * the device's identity, so it is minted once per install and survives
 * sign-outs (see the settings layer). Binary URLs handed to ExoPlayer/image
 * loaders ([PlexUrlBuilder]) are token-only — PMS does not require the
 * client identity there.
 */
data class PlexClientInfo(
    val clientId: String,
    val version: String,
) {
    fun headers(): List<Pair<String, String>> = listOf(
        "X-Plex-Client-Identifier" to clientId,
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Version" to version,
        "X-Plex-Platform" to PLATFORM,
        "X-Plex-Device" to DEVICE,
        "X-Plex-Device-Name" to PRODUCT,
    )

    companion object {
        const val PRODUCT = "Norrklang"
        const val PLATFORM = "Android"
        const val DEVICE = "Android"

        /** Informational X-Plex-Version fallback; not tied to the app version. */
        const val DEFAULT_VERSION = "1.0"
    }
}
