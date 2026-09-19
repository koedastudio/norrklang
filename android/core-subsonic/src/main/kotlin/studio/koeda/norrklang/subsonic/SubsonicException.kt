package studio.koeda.norrklang.subsonic

/** Single error hierarchy for everything that can go wrong talking to the server. */
sealed class SubsonicException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Wrong credentials or a rejected auth mechanism (Subsonic error codes 40–42, 44). */
    class AuthFailed(message: String, val code: Int? = null) : SubsonicException(message) {
        /**
         * The server cannot verify a salted token (41: LDAP-backed Subsonic,
         * Nextcloud Music; 42: OpenSubsonic "mechanism not supported") — the
         * same credentials may still work as a plain password.
         */
        val isTokenAuthUnsupported: Boolean
            get() = code == TOKEN_AUTH_UNSUPPORTED || code == AUTH_MECHANISM_UNSUPPORTED
    }

    /** The requested item does not exist (Subsonic error code 70). */
    class NotFound(message: String) : SubsonicException(message)

    /** The server answered, but with an error we don't have a dedicated type for. */
    class ServerError(val code: Int?, message: String, cause: Throwable? = null) :
        SubsonicException(message, cause)

    /** Could not reach the server at all (DNS, TLS, timeout, ...). */
    class NetworkError(cause: Throwable) :
        SubsonicException(cause.message ?: "Network error", cause)

    companion object {
        const val TOKEN_AUTH_UNSUPPORTED = 41
        const val AUTH_MECHANISM_UNSUPPORTED = 42

        fun fromErrorCode(code: Int, message: String?): SubsonicException {
            val text = message ?: "Subsonic error $code"
            return when (code) {
                40, TOKEN_AUTH_UNSUPPORTED, AUTH_MECHANISM_UNSUPPORTED, 44 -> AuthFailed(text, code)
                70 -> NotFound(text)
                else -> ServerError(code, text)
            }
        }
    }
}
