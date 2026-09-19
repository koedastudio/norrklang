package studio.koeda.norrklang.subsonic

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic authentication primitives (API 1.13.0+).
 *
 * Token auth carries `u` (username), `s` (random salt) and
 * `t = md5(password + salt)` — the password itself never goes over the wire.
 * Password auth carries `u` and `p = enc:<hex of password>` instead, for
 * servers that cannot verify a token.
 */
object SubsonicAuth {

    const val API_VERSION = "1.16.1"
    const val CLIENT_NAME = "norrklang"

    private val random = SecureRandom()
    private const val SALT_CHARS = "abcdef0123456789"

    fun generateSalt(length: Int = 12): String =
        buildString(length) {
            repeat(length) { append(SALT_CHARS[random.nextInt(SALT_CHARS.length)]) }
        }

    fun token(password: String, salt: String): String =
        MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The `p` parameter's hex form: survives any character without URL-encoding surprises. */
    fun encodePassword(password: String): String =
        "enc:" + password.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}
