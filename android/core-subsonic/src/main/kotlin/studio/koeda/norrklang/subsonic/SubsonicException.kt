package studio.koeda.norrklang.subsonic

/** Single error hierarchy for everything that can go wrong talking to the server. */
sealed class SubsonicException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Wrong username/password or token auth rejected (Subsonic error codes 40, 41, 44). */
    class AuthFailed(message: String) : SubsonicException(message)

    /** The requested item does not exist (Subsonic error code 70). */
    class NotFound(message: String) : SubsonicException(message)

    /** The server answered, but with an error we don't have a dedicated type for. */
    class ServerError(val code: Int?, message: String, cause: Throwable? = null) :
        SubsonicException(message, cause)

    /** Could not reach the server at all (DNS, TLS, timeout, ...). */
    class NetworkError(cause: Throwable) :
        SubsonicException(cause.message ?: "Network error", cause)

    companion object {
        fun fromErrorCode(code: Int, message: String?): SubsonicException {
            val text = message ?: "Subsonic error $code"
            return when (code) {
                40, 41, 44 -> AuthFailed(text)
                70 -> NotFound(text)
                else -> ServerError(code, text)
            }
        }
    }
}
