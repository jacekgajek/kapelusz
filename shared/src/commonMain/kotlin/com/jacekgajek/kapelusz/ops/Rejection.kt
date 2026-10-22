package com.jacekgajek.kapelusz.ops

import kotlinx.serialization.Serializable

@Serializable
enum class RejectionCode { FORBIDDEN, NOT_FOUND, INVALID, CONFLICT }

/** Why an operation was refused, as a stable key the apps translate for users. */
@Serializable
enum class RejectionReason {
    NOT_A_MEMBER,
    ROLE_REQUIRED,
    INVITE_ONLY,
    NOT_FOUND,
    DUPLICATE_ID,
    INVALID_INPUT,
    INVALID_AMOUNT,
    EMPTY_NAME,
    INVALID_SHARE,
    NOT_A_PARTICIPANT,
    ALREADY_PARTICIPANT,
    SAME_PARTY,
    NO_TREASURER,
    TREASURER_MUST_STAY,
    ARCHIVED_MEMBER,
    /** Archived events are read-only: restore it first (ITERATION_3 R10). */
    ARCHIVED_EVENT,
    /** Only settled events and drafts can be archived. */
    EVENT_UNSETTLED,
    OWNER_ROLE_FIXED,
    OWNERSHIP_NEEDS_ADMIN,
    MEMBER_LINKED,
    USER_ALREADY_MEMBER,
    MEMBER_HAS_HISTORY,
    EVENT_HAS_TRANSFERS,
    PARTICIPANT_HAS_MONEY,
    PARTICIPANT_HAS_TRANSFERS,
    PARTICIPANT_HAS_REFUNDS,
    NOT_REFUNDABLE_HERE,
    REFUND_TOO_LARGE,
    COST_BELOW_REFUNDED,
    COST_REFUNDED_FROM_FUND,
    CANNOT_SETTLE,
    TOO_MANY_ATTACHMENTS,
    ATTACHMENT_TOO_LARGE,
    FUND_NOT_EMPTY,
    NOT_UNDOABLE,
    CHANGED_SINCE,
    LIMIT_REACHED,
    /** The device could not store it (full, or its storage failed); only ever reported locally. */
    STORAGE_FAILED,
}

/** Why an operation was not applied: [reason] for users, [message] as a developer-facing English description. */
@Serializable
data class Rejection(val code: RejectionCode, val message: String, val reason: RejectionReason = RejectionReason.INVALID_INPUT)

class RejectedException(val rejection: Rejection) : Exception(rejection.message)

internal fun reject(code: RejectionCode, reason: RejectionReason, message: String): Nothing =
    throw RejectedException(Rejection(code, message, reason))

internal fun invalidIf(condition: Boolean, reason: RejectionReason, message: () -> String) {
    if (condition) reject(RejectionCode.INVALID, reason, message())
}

internal fun conflictIf(condition: Boolean, reason: RejectionReason, message: () -> String) {
    if (condition) reject(RejectionCode.CONFLICT, reason, message())
}

internal fun <T : Any> T?.orNotFound(what: () -> String): T =
    this ?: reject(RejectionCode.NOT_FOUND, RejectionReason.NOT_FOUND, "${what()} not found")
