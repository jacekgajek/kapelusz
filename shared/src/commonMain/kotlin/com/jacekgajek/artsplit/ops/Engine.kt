package com.jacekgajek.artsplit.ops

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.auth.Permissions
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Id

sealed interface ApplyResult {
    data class Applied(val band: Band) : ApplyResult
    data class Rejected(val rejection: Rejection) : ApplyResult
}

data class BatchResult(
    val band: Band,
    val accepted: List<Id>,
    val rejected: Map<Id, Rejection>,
    /** Already applied earlier (seq ≤ last seen); neither accepted nor rejected again. */
    val duplicates: List<Id>,
)

/** Permission check + reducer: the single entry point used by both clients and the server. */
object Engine {

    fun apply(band: Band, actor: Actor, op: Operation, opId: Id, timestamp: Long): ApplyResult {
        Permissions.check(band, actor, op)?.let { return ApplyResult.Rejected(it) }
        val memberId = (actor as? Actor.User)?.let { band.memberOfUser(it.userId)?.id }
        return try {
            ApplyResult.Applied(Reducer.apply(band, op, OpContext(opId, timestamp, memberId)))
        } catch (e: RejectedException) {
            ApplyResult.Rejected(e.rejection)
        }
    }

    /**
     * Applies a client's queued operations in order. Operations with a sequence number not above the client's last
     * applied one are skipped, which makes retried pushes idempotent. Rejected ops still advance the sequence.
     */
    fun applyBatch(band: Band, actor: Actor, envelopes: List<OpEnvelope>): BatchResult {
        var state = band
        val accepted = mutableListOf<Id>()
        val rejected = mutableMapOf<Id, Rejection>()
        val duplicates = mutableListOf<Id>()
        for (envelope in envelopes) {
            if (envelope.seq <= (state.lastSeq[envelope.clientId] ?: 0L)) {
                duplicates += envelope.opId
                continue
            }
            when (val result = apply(state, actor, envelope.op, envelope.opId, envelope.timestamp)) {
                is ApplyResult.Applied -> {
                    state = result.band
                    accepted += envelope.opId
                }
                is ApplyResult.Rejected -> rejected[envelope.opId] = result.rejection
            }
            state = state.copy(lastSeq = state.lastSeq + (envelope.clientId to envelope.seq))
        }
        return BatchResult(state, accepted, rejected, duplicates)
    }
}
