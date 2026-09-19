package studio.koeda.norrklang.subsonic

import java.security.MessageDigest

/**
 * How every request proves who it is. Either variant is password-equivalent
 * to whoever holds it, so both redact their secret from `toString`.
 */
sealed interface SubsonicAuthMode {
    /** The secret that identifies this sign-in in the cache namespace. */
    val secret: String

    /** The query parameters this mode contributes to every request. */
    fun params(): List<Pair<String, String>>
}

/**
 * A precomputed Subsonic token-auth pair: `token = md5(password + salt)`.
 *
 * Computed once at sign-in and reused verbatim afterwards — the Subsonic
 * scheme explicitly allows reusing a salt, so neither disk nor memory ever
 * needs to hold the plaintext password past the sign-in call.
 */
data class SubsonicTokenAuth(val salt: String, val token: String) : SubsonicAuthMode {
    init {
        require(salt.isNotBlank()) { "salt must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
    }

    override val secret: String get() = token

    override fun params(): List<Pair<String, String>> = listOf("t" to token, "s" to salt)

    override fun toString(): String = "SubsonicTokenAuth(salt=$salt, token=<redacted>)"
}

/**
 * The password itself, sent hex-encoded as `p=enc:…` on every request.
 *
 * Only for servers that reject token auth (error 41/42): Nextcloud Music
 * and LDAP-backed Subsonic store a hash they cannot salt with MD5.
 */
data class SubsonicPasswordAuth(val password: String) : SubsonicAuthMode {
    init {
        require(password.isNotBlank()) { "password must not be blank" }
    }

    override val secret: String get() = password

    override fun params(): List<Pair<String, String>> =
        listOf("p" to SubsonicAuth.encodePassword(password))

    override fun toString(): String = "SubsonicPasswordAuth(password=<redacted>)"
}

/**
 * Connection details for one Navidrome/Subsonic server.
 *
 * [baseUrl] is normalized without a trailing slash, e.g. `https://music.example.com`.
 */
data class SubsonicCredentials(
    val baseUrl: String,
    val username: String,
    val auth: SubsonicAuthMode,
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
        listOf("u" to username) + auth.params() + listOf(
            "v" to SubsonicAuth.API_VERSION,
            "c" to SubsonicAuth.CLIENT_NAME,
        )

    /** The same server and account, authenticating with the password itself. */
    fun withPasswordAuth(password: String): SubsonicCredentials =
        copy(auth = SubsonicPasswordAuth(password))

    /**
     * Opaque identity of this (server, account, secret) triple, safe as a
     * cache namespace: cached data can't leak across sign-ins, and including
     * the secret means a re-sign-in also starts from a cold cache.
     */
    val cacheFingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\n$username\n${auth.secret}".toByteArray())
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
