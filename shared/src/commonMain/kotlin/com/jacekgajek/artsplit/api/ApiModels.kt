package com.jacekgajek.artsplit.api

import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.Rejection
import kotlinx.serialization.Serializable

/** Request and response bodies of the HTTP API (REFINED_PLAN §5.3), shared by the server and the apps. */

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
    /** The band does not exist or the caller is not (or no longer) an active member. */
    const val BAND_NOT_FOUND = "band_not_found"

    /** No active invite with this code (wrong, used, revoked or expired). */
    const val INVITE_NOT_FOUND = "invite_not_found"

    /** Too many wrong invite codes recently; try again later. */
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"

    /** The caller is already a member of the invited band. */
    const val ALREADY_MEMBER = "already_member"
}

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

/** One accepted push: who made it and the operations it contained. */
@Serializable
data class HistoryEntry(val userId: String, val memberId: String?, val at: Long, val ops: List<OpEnvelope>)

/** History of one calendar month (UTC), oldest first. */
@Serializable
data class HistoryResponse(val month: String, val entries: List<HistoryEntry>)
