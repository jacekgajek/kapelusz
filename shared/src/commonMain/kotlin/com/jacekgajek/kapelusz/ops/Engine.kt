package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.auth.Permissions
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.RecordedRejection
import com.jacekgajek.kapelusz.model.Rejection

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
    /** What each accepted operation changed, for undo (ITERATION_2 §7.2). */
    val changes: Map<Id, BandChange> = emptyMap(),
)

/** Permission check + reducer: the single entry point used by both clients and the server. */
object Engine {
    /** Refusals remembered per client install for re-reporting on retried pushes. */
    const val REMEMBERED_REJECTIONS = 20

    fun apply(band: Band, actor: Actor, op: Operation, opId: Id, timestamp: Long): ApplyResult {
        Permissions.check(band, actor, op)?.let { return ApplyResult.Rejected(it) }
        val memberId = (actor as? Actor.User)?.let { band.memberOfUser(it.userId)?.id }
        return try {
            val result = Reducer.apply(band, op, OpContext(opId, timestamp, memberId))
            Limits.violation(band, result)?.let { return ApplyResult.Rejected(it) }
            ApplyResult.Applied(result)
        } catch (e: RejectedException) {
            ApplyResult.Rejected(e.rejection)
        }
    }

    /**
     * Applies a client's queued operations in order. Operations with a sequence number not above the client's last
     * applied one are skipped, which makes retried pushes idempotent. Rejected ops still advance the sequence.
     */
    fun applyBatch(band: Band, actor: Actor, envelopes: List<OpEnvelope>, check: ((before: Band, after: Band) -> Rejection?)? = null): BatchResult {
        var state = band
        val accepted = mutableListOf<Id>()
        val rejected = mutableMapOf<Id, Rejection>()
        val duplicates = mutableListOf<Id>()
        val changes = mutableMapOf<Id, BandChange>()
        for (envelope in envelopes) {
            if (envelope.seq <= (state.lastSeq[envelope.clientId] ?: 0L)) {
                // Already seen. If it was refused, say so again: the client may never have received the first answer.
                val earlier = state.recentRejections[envelope.clientId]?.firstOrNull { it.opId == envelope.opId }
                if (earlier != null) rejected[envelope.opId] = earlier.rejection else duplicates += envelope.opId
                continue
            }
            // An extra check of the caller (the server: the band's size) refuses an operation like the reducer would.
            val applied = apply(state, actor, envelope.op, envelope.opId, envelope.timestamp)
            val result = if (applied is ApplyResult.Applied && check != null) check(state, applied.band)?.let { ApplyResult.Rejected(it) } ?: applied else applied
            when (result) {
                is ApplyResult.Applied -> {
                    changes[envelope.opId] = BandChange.between(state, result.band)
                    state = result.band
                    accepted += envelope.opId
                }

                is ApplyResult.Rejected -> {
                    rejected[envelope.opId] = result.rejection
                    val remembered = (state.recentRejections[envelope.clientId].orEmpty() + RecordedRejection(envelope.opId, result.rejection))
                        .takeLast(REMEMBERED_REJECTIONS)
                    state = state.copy(recentRejections = state.recentRejections + (envelope.clientId to remembered))
                }
            }
            state = state.copy(lastSeq = state.lastSeq + (envelope.clientId to envelope.seq))
        }
        return BatchResult(state, accepted, rejected, duplicates, changes)
    }
}
