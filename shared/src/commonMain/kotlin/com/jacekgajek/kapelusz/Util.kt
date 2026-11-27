package com.jacekgajek.kapelusz

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.datetime.LocalDate

fun dateFilter(date: LocalDate, from: LocalDate?, to: LocalDate?): Boolean = if (from != null && to != null) {
    date in from..to
} else if (from != null) {
    date >= from
} else if (to != null) {
    date <= to
} else {
    true
}

inline fun <R> catching(f: () -> R): Result<R> = runCatching(f).onFailure { throwable ->
    if (throwable is CancellationException) {
        throw throwable
    }
}

/**
 * Applies the given [block] to the value inside this `Result` if successful, and returns a new `Result`.
 *
 * @param block The function to be applied if this `Result` is successful.
 * @return A new `Result` containing the transformed value or the original error.
 */
inline fun <T, R> Result<T>.flatMap(block: (T) -> Result<R>): Result<R> = fold(block) { Result.failure(it) }
