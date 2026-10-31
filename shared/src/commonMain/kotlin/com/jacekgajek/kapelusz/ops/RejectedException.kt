package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

// How the reducers refuse an operation: a RejectedException carrying the Rejection the caller reports.

class RejectedException(val rejection: Rejection) : Exception(rejection.message)

internal fun reject(code: RejectionCode, reason: RejectionReason, message: String): Nothing = throw RejectedException(Rejection(code, message, reason))

/** Throws when [condition] holds; so after it returns, the condition is known to be false (a `null` check smart-casts). */
@OptIn(ExperimentalContracts::class)
internal fun invalidIf(condition: Boolean, reason: RejectionReason, message: () -> String) {
    contract { returns() implies !condition }
    if (condition) reject(RejectionCode.INVALID, reason, message())
}

@OptIn(ExperimentalContracts::class)
internal fun conflictIf(condition: Boolean, reason: RejectionReason, message: () -> String) {
    contract { returns() implies !condition }
    if (condition) reject(RejectionCode.CONFLICT, reason, message())
}

internal fun <T : Any> T?.orNotFound(what: () -> String): T = this ?: reject(RejectionCode.NOT_FOUND, RejectionReason.NOT_FOUND, "${what()} not found")
