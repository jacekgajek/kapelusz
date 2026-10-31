package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.Rejection

/** The user's changes to the open band: applied here at once, then sent by [sync]. */
class Changes(private val bands: BandRepository, private val sync: SyncEngine) {

    /** Applies an operation locally and schedules a sync. */
    fun dispatch(op: Operation): Rejection? {
        val rejection = bands.dispatch(op)
        if (rejection == null) {
            sync.requestSync()
        }
        return rejection
    }

    /**
     * Applies an operation locally but holds it back from syncing for [holdFor] ms, while its Undo snackbar shows
     * (ITERATION_5 §2); [onHeld] gets it. Returns the rejection, if any.
     */
    fun dispatchHeld(op: Operation, holdFor: Long, onHeld: (HeldOp) -> Unit): Rejection? {
        val bandId = bands.currentBandId ?: error("No band open")
        bands.dispatch(op, holdFor)?.let { return it }
        onHeld(HeldOp(bandId, bands.lastOpId!!))
        return null
    }

    /** Lets a held operation sync now (its snackbar is gone). */
    fun release(held: HeldOp) {
        bands.release(held.bandId, held.opId)
        sync.requestSync()
    }

    /** Takes a held operation back; false when it may already be on its way (then only the history can undo it). */
    fun withdraw(held: HeldOp): Boolean {
        if (!bands.withdraw(held.bandId, held.opId)) return false
        // What was queued behind it waited for it, and goes now.
        sync.requestSync()
        return true
    }
}

/** An operation held back from syncing while its Undo snackbar shows. */
data class HeldOp(val bandId: Id, val opId: Id)
