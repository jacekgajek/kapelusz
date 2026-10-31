package com.jacekgajek.kapelusz.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * [runCatching] for suspend calls: a failure becomes a [Result], but cancellation is rethrown, so a coroutine that was
 * cancelled (e.g. its screen closed) stops instead of carrying on with a "failed" result (I3 R12).
 * Throwable, not only Exception: on the web, network errors are JsException.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Runs [block] in this (the app's) scope and waits for it: work that must finish even when the screen that asked
 * for it leaves (signing in, leaving a band), as the screen's scope would cancel it half-way. Cancelling the caller
 * leaves the work running; a failure is thrown to the caller.
 */
suspend fun <T> CoroutineScope.detached(block: suspend () -> T): T = async { block() }.await()
