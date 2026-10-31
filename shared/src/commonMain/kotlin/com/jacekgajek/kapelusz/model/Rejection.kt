package com.jacekgajek.kapelusz.model

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
    INVALID_PHONE,
    INVALID_ACCOUNT,
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

    /** Artists never hold money: no collected money, no band fund (ITERATION_4 §1.1). */
    ARTIST_HOLDS_NO_MONEY,

    /** Viewers see the band's money but take no part in events. */
    VIEWER_NOT_PARTICIPANT,

    /** Someone still playing in an unsettled event, or holding the band fund, cannot become a viewer (V5-M1 B2). */
    VIEWER_STILL_PLAYS,

    /** Someone holding money in an unsettled event, or the band fund, cannot become an artist. */
    HOLDS_MONEY,
    OWNER_ROLE_FIXED,
    OWNERSHIP_NEEDS_LEADER,
    NAME_TAKEN,
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

    /** Someone in a pair or with an invitation waiting, or who can't settle together (ITERATION_5 §7). */
    CANNOT_SETTLE_TOGETHER,
}

/** Why an operation was not applied: [reason] for users, [message] as a developer-facing English description. */
@Serializable
data class Rejection(val code: RejectionCode, val message: String, val reason: RejectionReason = RejectionReason.INVALID_INPUT)

/** A refusal remembered in [Band.recentRejections]. */
@Serializable
data class RecordedRejection(val opId: Id, val rejection: Rejection)
