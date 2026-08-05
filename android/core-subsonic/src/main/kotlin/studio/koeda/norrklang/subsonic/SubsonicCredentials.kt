package studio.koeda.norrklang.subsonic

import java.security.MessageDigest

/**
 * A precomputed Subsonic token-auth pair: `token = md5(password + salt)`.
 *
 * Computed once at sign-in and reused verbatim afterwards — the Subsonic
 * scheme explicitly allows reusing a salt, so neither disk nor memory ever
 * needs to hold the plaintext password past the sign-in call.
 */
data class SubsonicTokenAuth(val salt: String, val token: String) {
    init {
        require(salt.isNotBlank()) { "salt must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
    }

    // The token plus the public salt is password-equivalent; redact it so the
    // generated data-class toString can't leak it into logs or crash reports.
    override fun toString(): String = "SubsonicTokenAuth(salt=$salt, token=<redacted>)"
}

/**
 * Connection details for one Navidrome/Subsonic server.
 *
 * [baseUrl] is normalized without a trailing slash, e.g. `https://music.example.com`.
 */
data class SubsonicCredentials(
    val baseUrl: String,
    val username: String,
    val auth: SubsonicTokenAuth,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
    }

    /**
     * True for an explicitly chosen cleartext `http://` server — username and
     * replayable token then travel unencrypted, so UI layers should warn.
     * Release builds also refuse cleartext via network security config; this
     * only ever succeeds in debug builds.
     */
    val isCleartext: Boolean
        get() = baseUrl.startsWith("http://")

    /**
     * The auth/protocol query parameters every request carries — single
     * source so [SubsonicClient] and [SubsonicUrlBuilder] cannot drift.
     */
    fun authParams(): List<Pair<String, String>> =
        listOf(
            "u" to username,
            "t" to auth.token,
            "s" to auth.salt,
            "v" to SubsonicAuth.API_VERSION,
            "c" to SubsonicAuth.CLIENT_NAME,
        )

    /**
     * Opaque identity of this (server, account, token) triple, safe as a
     * cache namespace: cached data can't leak across sign-ins, and including
     * the token means a re-sign-in also starts from a cold cache.
     */
    val cacheFingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\n$username\n${auth.token}".toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        /**
         * Normalizes user input (default https://, strip trailing slashes)
         * and derives the token-auth pair from [password], which is not
         * retained. Explicit `http://` is kept for local test servers — see
         * [isCleartext].
         */
        fun fromInput(url: String, username: String, password: String): SubsonicCredentials {
            val trimmed = url.trim().trimEnd('/')
            val withScheme =
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
                else "https://$trimmed"
            val salt = SubsonicAuth.generateSalt()
            return SubsonicCredentials(
                baseUrl = withScheme,
                username = username.trim(),
                auth = SubsonicTokenAuth(salt = salt, token = SubsonicAuth.token(password, salt)),
            )
        }
    }
}
