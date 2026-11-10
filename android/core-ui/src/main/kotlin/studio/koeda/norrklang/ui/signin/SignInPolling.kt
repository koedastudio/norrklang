package studio.koeda.norrklang.ui.signin

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Cadence of the Plex PIN and Jellyfin Quick Connect polls. */
internal const val SIGN_IN_POLL_INTERVAL_MS = 2_000L

/** Consecutive failed polls tolerated before a link flow gives up. */
internal const val MAX_POLL_FAILURES = 5

/** How long a link flow keeps polling (and renewing its code) before it stops. */
internal const val SIGN_IN_POLL_TIMEOUT_MS = 30 * 60_000L

/** A link flow nobody completed within its timeout: the page left open must not poll forever. */
internal class SignInPollTimeoutException : Exception("Sign-in code expired")

/**
 * Waits [intervalMs], runs [poll], and repeats until it returns non-null.
 * A dropped poll (flaky in-car connectivity) must not abort a link the user
 * is mid-way through on their phone: failures accepted by [isTransient] are
 * swallowed until [maxFailures] happen in a row, then the last one is thrown.
 * Throws [SignInPollTimeoutException] after [timeoutMs] without a result.
 */
internal suspend fun <T : Any> pollUntil(
    intervalMs: Long,
    timeoutMs: Long = SIGN_IN_POLL_TIMEOUT_MS,
    maxFailures: Int = MAX_POLL_FAILURES,
    isTransient: (Exception) -> Boolean,
    poll: suspend () -> T?,
): T = withTimeoutOrNull(timeoutMs) {
    var failures = 0
    var result: T? = null
    while (result == null) {
        delay(intervalMs)
        result = try {
            poll()
        } catch (e: Exception) {
            if (e is CancellationException || !isTransient(e)) throw e
            if (++failures >= maxFailures) throw e
            continue
        }
        failures = 0
    }
    result
} ?: throw SignInPollTimeoutException()
