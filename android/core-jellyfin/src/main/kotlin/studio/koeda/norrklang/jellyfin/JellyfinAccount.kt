package studio.koeda.norrklang.jellyfin

import java.security.MessageDigest

/**
 * Everything needed to talk to one Jellyfin server: the normalized base URL
 * (no trailing slash, may carry a path prefix), the user's access token, and
 * the music library (view) to browse.
 */
data class JellyfinAccount(
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val username: String,
    val token: String,
    val libraryId: String,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
        require(libraryId.isNotBlank()) { "libraryId must not be blank" }
    }

    /**
     * Opaque identity of this (server, user, token) triple, safe as a cache
     * namespace: cached data can't leak across sign-ins, and including the
     * token means a re-sign-in also starts from a cold cache.
     */
    val cacheFingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\n$userId\n$token".toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // The token is password-equivalent; redact it so the generated data-class
    // toString can't leak it into logs or crash reports.
    override fun toString(): String =
        "JellyfinAccount(baseUrl=$baseUrl, serverName=$serverName, " +
            "userId=$userId, username=$username, token=<redacted>, " +
            "libraryId=$libraryId)"

    companion object {
        /**
         * Same normalization as the Subsonic form: trim, drop trailing
         * slashes, default to https. Path segments are preserved — Jellyfin
         * is commonly hosted under a base path (e.g. example.com/jellyfin).
         */
        fun normalizeBaseUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        }
    }
}
