package com.jacekgajek.kapelusz.app.data

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspend calls: a failure becomes a [Result], but cancellation is rethrown, so a coroutine that was
 * cancelled (e.g. its screen closed) stops instead of carrying on with a "failed" result (ITERATION_3 R12).
 * Throwable, not only Exception: on the web, network errors are JsException.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
