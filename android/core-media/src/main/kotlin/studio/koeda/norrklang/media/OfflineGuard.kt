package studio.koeda.norrklang.media

import kotlinx.coroutines.withTimeoutOrNull
import studio.koeda.norrklang.data.repo.MusicException

/**
 * Keeps a play request from hanging, or from emptying the queue, when the
 * car has no connection: what resolves from memory or disk goes through,
 * anything else ends in [NoConnection] quickly.
 */
internal class OfflineGuard(
    private val isOnline: () -> Boolean,
    private val deadlineMs: Long = DEADLINE_MS,
) {

    /** The request could not be resolved for lack of a connection; the queue must stay as it is. */
    class NoConnection(cause: Throwable? = null) : Exception("No connection", cause)

    /** [failedSilently] tells a result that hides a failed load (an empty mix) from a real one. */
    suspend fun <T : Any> run(failedSilently: (T) -> Boolean, block: suspend () -> T): T {
        val online = isOnline()
        val result = try {
            if (online) block() else withTimeoutOrNull(deadlineMs) { block() }
        } catch (e: MusicException.NetworkError) {
            throw NoConnection(e)
        }
        if (result == null || (!online && failedSilently(result))) throw NoConnection()
        return result
    }

    private companion object {
        /**
         * A deadline, not a refusal: "not validated" also describes a home
         * server on Wi-Fi without internet, where requests do succeed.
         */
        const val DEADLINE_MS = 2_500L
    }
}
