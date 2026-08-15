package studio.koeda.norrklang.data.repo

/**
 * Backend-neutral error hierarchy for everything above the provider modules.
 *
 * Provider clients (core-subsonic, core-plex) throw their own exception types;
 * core-data translates them at the repository/session boundary so core-media
 * and the UI never depend on a specific server API.
 */
sealed class MusicException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The server rejected our credentials. */
    class AuthFailed(message: String) : MusicException(message)

    /** The requested item does not exist on the server. */
    class NotFound(message: String) : MusicException(message)

    /** The server answered, but with an error we don't have a dedicated type for. */
    class ServerError(message: String, cause: Throwable? = null) :
        MusicException(message, cause)

    /** Could not reach the server at all (DNS, TLS, timeout, ...). */
    class NetworkError(cause: Throwable) :
        MusicException(cause.message ?: "Network error", cause)
}
