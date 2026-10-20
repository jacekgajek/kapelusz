package com.jacekgajek.artsplit.ops

import kotlinx.serialization.Serializable

@Serializable
enum class RejectionCode { FORBIDDEN, NOT_FOUND, INVALID, CONFLICT }

/** Why an operation was not applied; [message] is a developer-facing English description. */
@Serializable
data class Rejection(val code: RejectionCode, val message: String)

class RejectedException(val rejection: Rejection) : Exception(rejection.message)

internal fun reject(code: RejectionCode, message: String): Nothing = throw RejectedException(Rejection(code, message))

internal fun invalidIf(condition: Boolean, message: () -> String) {
    if (condition) reject(RejectionCode.INVALID, message())
}

internal fun conflictIf(condition: Boolean, message: () -> String) {
    if (condition) reject(RejectionCode.CONFLICT, message())
}

internal fun <T : Any> T?.orNotFound(what: () -> String): T = this ?: reject(RejectionCode.NOT_FOUND, "${what()} not found")
