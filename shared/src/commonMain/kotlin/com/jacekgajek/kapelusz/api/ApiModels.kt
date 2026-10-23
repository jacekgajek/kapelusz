package com.jacekgajek.kapelusz.api

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.Rejection
import kotlinx.serialization.Serializable

/** Request and response bodies of the HTTP API (ITERATION_1 §5.3), shared by the server and the apps. */

@Serializable
data class BandSummary(val id: Id, val name: String, val memberName: String, val role: Role)

@Serializable
data class MeResponse(val userId: String, val email: String? = null, val name: String? = null, val bands: List<BandSummary>)

/** Ids come from the client, so a band created offline keeps its ids when it is uploaded. */
@Serializable
data class CreateBandRequest(
    val bandId: Id,
    val name: String,
    val currency: String,
    val ownerMemberId: Id,
    val ownerName: String,
)

/** [version] is the storage ETag; send it back as `If-None-Match` to get `304 Not Modified`. */
@Serializable
data class BandStateResponse(val version: String, val band: Band)

@Serializable
data class PushRequest(val ops: List<OpEnvelope>)

@Serializable
data class PushResponse(
    val version: String,
    val band: Band,
    val accepted: List<Id>,
    val rejected: Map<Id, Rejection>,
    val duplicates: List<Id>,
)

@Serializable
data class ErrorResponse(val error: String, val message: String)

object ApiErrors {
    /** The push would make the band's file too large to load (`413`); nothing was written. */
    const val BAND_TOO_LARGE = "band_too_large"

    /** The user is in as many bands as one account may be (`409`). */
    const val TOO_MANY_BANDS = "too_many_bands"

    /** The band does not exist or the caller is not (or no longer) an active member. */
    const val BAND_NOT_FOUND = "band_not_found"

    /** The app is older than the server supports (`426`); it has to be updated before it can sync again. */
    const val UPDATE_REQUIRED = "update_required"

    /** Query parameter carrying the app's version code on every request; 0 for local development builds. */
    const val APP_VERSION_PARAMETER = "appVersion"

    /** The band was deleted by its owner; only its former members get this (others get [BAND_NOT_FOUND]). */
    const val BAND_DELETED = "band_deleted"

    /** No active invite with this code (wrong, used, revoked or expired). */
    const val INVITE_NOT_FOUND = "invite_not_found"

    /** Too many wrong invite codes recently; try again later. */
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"

    /** The caller is already a member of the invited band. */
    const val ALREADY_MEMBER = "already_member"

    /** An artist invite for someone who still holds band money (ITERATION_4 §1.1): settle it first (`409`). */
    const val HOLDS_MONEY = "holds_money"

    /**
     * No such attachment on the cost (it was removed, or its operation refused). Distinct from a generic 404 so a
     * client only ever discards a waiting file on this answer, never on e.g. an older server without the route.
     */
    const val ATTACHMENT_NOT_FOUND = "attachment_not_found"

    /** The attachment is listed on its cost, but its file has not been uploaded yet. */
    const val ATTACHMENT_NOT_UPLOADED = "attachment_not_uploaded"

    /** The attachment's file is already uploaded (files are never replaced). */
    const val ATTACHMENT_EXISTS = "attachment_exists"

    /**
     * The token (a session or a Google ID token) was issued before the user's "sign out on all devices" (`401`). The
     * app must not renew it silently: it signs out, and the user signs in with Google again.
     */
    const val SESSIONS_REVOKED = "sessions_revoked"

    // The generic codes (ITERATION_3 R12: every code the server sends is listed here).

    /** The request is malformed or breaks a limit (`400`). */
    const val BAD_REQUEST = "bad_request"

    /** No token, or an invalid or expired one (`401`); also a session too old to renew. */
    const val UNAUTHORIZED = "unauthorized"

    /** The caller's role does not allow this (`403`). */
    const val FORBIDDEN = "forbidden"

    /** No such route, member or calendar feed (`404`). */
    const val NOT_FOUND = "not_found"

    /** The band id is taken by someone else's band (`409`). */
    const val CONFLICT = "conflict"

    /** Too many concurrent writes to the same data (`409`); the same request can simply be sent again. */
    const val BUSY = "busy"

    /** The user already uses as many devices in this band as one account may (`400`). */
    const val TOO_MANY_DEVICES = "too_many_devices"

    /** An unexpected server error (`500`). */
    const val INTERNAL = "internal"
}

// ---- sessions ----

/**
 * A Kapelusz session token (from `POST /session`, called with a Google ID token or a current session token) and
 * its expiry in epoch millis. Send it as the bearer token instead of the Google one.
 */
@Serializable
data class SessionResponse(val token: String, val expiresAt: Long)

// ---- calendar subscription (V2) ----

/** The user's private calendar feed link (`webcal`-able) and whether it includes events they do not take part in. */
@Serializable
data class CalendarSubscription(val url: String, val includeAll: Boolean)

/** Changes the subscription: [reset] makes a new link (the old one stops working). */
@Serializable
data class CalendarSubscriptionChange(val includeAll: Boolean? = null, val reset: Boolean = false)

// ---- attachments (M5) ----

/** Asks to upload the file of an attachment already in the band (its `AddAttachment` was pushed first). */
@Serializable
data class AttachmentUploadRequest(val attachmentId: Id)

/** A short-lived URL to `PUT` the file to, with the headers the request must carry exactly. */
@Serializable
data class AttachmentUploadResponse(val putUrl: String, val headers: Map<String, String>)

/** A short-lived URL to download (open) an attachment. */
@Serializable
data class AttachmentUrlResponse(val url: String)

// ---- invites (M4) ----

@Serializable
data class CreateInviteRequest(val memberId: Id, val role: Role)

/** An active invite code; only band admins see these. */
@Serializable
data class InviteInfo(val code: String, val bandId: Id, val memberId: Id, val role: Role, val expiresAt: Long)

@Serializable
data class InvitesResponse(val invites: List<InviteInfo>)

/** What the invited person sees before accepting. */
@Serializable
data class InvitePreview(val code: String, val bandId: Id, val bandName: String, val memberName: String, val role: Role, val expiresAt: Long)

// ---- history (M4) ----

/**
 * One accepted push: who made it and the operations it contained, with what each of them changed ([changes] by opId,
 * for undo; missing in entries from before V2-M7).
 */
@Serializable
data class HistoryEntry(
    val userId: String,
    val memberId: String?,
    val at: Long,
    val ops: List<OpEnvelope>,
    val changes: Map<String, BandChange> = emptyMap(),
    /** The entry's name in the log ("2026-10/…json"), set when listed; an undo names it. */
    val id: String? = null,
)

/** One page of a calendar month's history (UTC), newest first; pass [next] as `before` for the following page. */
@Serializable
data class HistoryResponse(val month: String, val entries: List<HistoryEntry>, val next: String? = null)

/** POST /me/picture: a Google sign-in token of the user (may have expired); its photo becomes theirs in their bands. */
@Serializable
data class PictureRequest(val googleToken: String)
