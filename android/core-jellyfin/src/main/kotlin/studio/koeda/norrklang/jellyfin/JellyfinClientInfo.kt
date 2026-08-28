package studio.koeda.norrklang.jellyfin

/**
 * The `Authorization: MediaBrowser ...` identity every API request carries.
 * [deviceId] is Jellyfin's device identity: each (client, deviceId) pair is a
 * "device" in the server's dashboard, so it is minted once per install and
 * survives sign-outs (see the settings layer) — same rationale as the
 * X-Plex-Client-Identifier. Binary URLs handed to ExoPlayer/image loaders
 * ([JellyfinUrlBuilder]) are token-only via the api_key query parameter.
 */
data class JellyfinClientInfo(
    val deviceId: String,
    val version: String,
) {
    /** Authorization header value; the token rides along once signed in. */
    fun authorizationHeader(token: String? = null): String =
        "MediaBrowser Client=\"$PRODUCT\", Device=\"$DEVICE\", " +
            "DeviceId=\"$deviceId\", Version=\"$version\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    companion object {
        const val PRODUCT = "Norrklang"
        const val DEVICE = "Android"

        /** Informational version fallback; not tied to the app version. */
        const val DEFAULT_VERSION = "1.0"
    }
}
