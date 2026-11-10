package studio.koeda.norrklang.ui.signin

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/** Cadence of the Plex PIN and Jellyfin Quick Connect polls. */
internal const val SIGN_IN_POLL_INTERVAL_MS = 2_000L

/** Consecutive failed polls tolerated before a link flow gives up. */
internal const val MAX_POLL_FAILURES = 5

/**
 * Waits [intervalMs], runs [poll], and repeats until it returns non-null.
 * A dropped poll (flaky in-car connectivity) must not abort a link the user
 * is mid-way through on their phone: failures accepted by [isTransient] are
 * swallowed until [maxFailures] happen in a row, then the last one is thrown.
 */
internal suspend fun <T : Any> pollUntil(
    intervalMs: Long,
    maxFailures: Int = MAX_POLL_FAILURES,
    isTransient: (Exception) -> Boolean,
    poll: suspend () -> T?,
): T {
    var failures = 0
    while (true) {
        delay(intervalMs)
        val result = try {
            poll()
        } catch (e: Exception) {
            if (e is CancellationException || !isTransient(e)) throw e
            if (++failures >= maxFailures) throw e
            continue
        }
        failures = 0
        if (result != null) return result
    }
}
