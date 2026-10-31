package com.jacekgajek.kapelusz.history

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.Operation
import kotlinx.serialization.Serializable

/** An operation as queued by a client and pushed to the server. */
@Serializable
data class OpEnvelope(
    val opId: Id,
    val clientId: String,
    val seq: Long,
    val timestamp: Long,
    val op: Operation,
)
