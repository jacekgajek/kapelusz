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
