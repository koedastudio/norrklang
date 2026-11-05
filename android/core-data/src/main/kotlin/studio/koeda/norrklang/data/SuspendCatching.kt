package studio.koeda.norrklang.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend code: cancellation propagates instead of being
 * swallowed as a failure. Catches [Exception] only, never [Error].
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
