package studio.koeda.norrklang.plex

import java.security.MessageDigest

/**
 * Everything needed to talk to one Plex Media Server: the chosen connection
 * URI (no trailing slash), the server's per-user access token, and the music
 * library section to browse.
 */
data class PlexAccount(
    val serverUri: String,
    val serverName: String,
    val machineIdentifier: String,
    val token: String,
    val sectionId: String,
    val username: String,
) {
    init {
        require(serverUri.isNotBlank()) { "serverUri must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
    }

    /**
     * Opaque identity of this (server, connection, token) triple, safe as a
     * cache namespace: cached data can't leak across sign-ins, and including
     * the token means a re-link also starts from a cold cache.
     */
    val cacheFingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$serverUri\n$machineIdentifier\n$token".toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // The token is password-equivalent; redact it so the generated data-class
    // toString can't leak it into logs or crash reports.
    override fun toString(): String =
        "PlexAccount(serverUri=$serverUri, serverName=$serverName, " +
            "machineIdentifier=$machineIdentifier, token=<redacted>, " +
            "sectionId=$sectionId, username=$username)"
}
