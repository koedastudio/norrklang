package studio.koeda.norrklang.jellyfin

/** Single error hierarchy for everything that can go wrong talking to Jellyfin. */
sealed class JellyfinException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The token was rejected (HTTP 401) or access denied (HTTP 403). */
    class AuthFailed(message: String) : JellyfinException(message)

    /** The requested item does not exist (HTTP 404). */
    class NotFound(message: String) : JellyfinException(message)

    /** The server answered, but with an error we don't have a dedicated type for. */
    class ServerError(val code: Int?, message: String, cause: Throwable? = null) :
        JellyfinException(message, cause)

    /** Could not reach [source] at all (DNS, TLS, timeout, ...). */
    class NetworkError(cause: Throwable, source: String? = null) : JellyfinException(
        listOfNotNull(source, cause.message ?: "Network error").joinToString(": "),
        cause,
    )

    companion object {
        fun fromStatusCode(code: Int, source: String): JellyfinException = when (code) {
            401, 403 -> AuthFailed("HTTP $code from $source")
            404 -> NotFound("HTTP 404 from $source")
            else -> ServerError(code, "HTTP $code from $source")
        }
    }
}
