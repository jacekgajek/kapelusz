package com.jacekgajek.artsplit.app.ui

import com.jacekgajek.artsplit.api.HistoryEntry
import com.jacekgajek.artsplit.app.ui.screens.HistoryLine
import com.jacekgajek.artsplit.app.ui.screens.pendingUndoLine
import com.jacekgajek.artsplit.app.ui.screens.undoTargets
import com.jacekgajek.artsplit.app.ui.screens.withPending
import com.jacekgajek.artsplit.ops.BandChange
import com.jacekgajek.artsplit.ops.BandPart
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UndoTargetsTest {
    private val none = BandChange(BandPart(), BandPart())
    private fun line(id: String, op: Operation) = HistoryLine(HistoryEntry("u", "m", 0, emptyList()), OpEnvelope(id, "c", 0, 0, op), id)
    private fun change(id: String) = line(id, RenameBand(id))
    private fun undo(id: String, target: HistoryLine) = line(id, Undo(target.opId, target.envelope!!.op, none))

    @Test
    fun aNewChangeAfterAnUndoEndsRedo() {
        // A, B, undo B, C — shown newest first.
        val a = change("A"); val b = change("B"); val undoB = undo("uB", b); val c = change("C")
        val targets = undoTargets(listOf(c, undoB, b, a))
        assertEquals("C", targets.undo?.opId)
        assertNull(targets.redo)
        assertEquals(setOf("B"), targets.undone)
    }

    @Test
    fun undoStepsBackAndRedoGoesForward() {
        val a = change("A"); val b = change("B"); val undoB = undo("uB", b); val c = change("C"); val undoC = undo("uC", c)
        val afterUndoC = undoTargets(listOf(undoC, c, undoB, b, a))
        assertEquals("A", afterUndoC.undo?.opId, "B is already undone, so the next step back is A")
        assertEquals("uC", afterUndoC.redo?.opId)

        val redoC = undo("rC", undoC)
        val afterRedo = undoTargets(listOf(redoC, undoC, c, undoB, b, a))
        assertEquals("C", afterRedo.undo?.opId)
        assertNull(afterRedo.redo, "redo is not offered for a redo")
        assertEquals(setOf("uC", "B"), afterRedo.undone)
    }

    @Test
    fun changesThatCannotBeUndoneAreSteppedOver() {
        val a = change("A"); val joined = change("join")
        val targets = undoTargets(listOf(joined, a)) { it.opId != "join" }
        assertEquals("A", targets.undo?.opId)
    }

    @Test
    fun anUndoShowsAtOnceSoTheNextTapStepsFurtherBack() {
        val a = change("A"); val b = change("B"); val c = change("C")
        val server = listOf(c, b, a)
        val undoC = pendingUndoLine(Undo("C", RenameBand("C"), none), "u1", "u", "m")
        val undoB = pendingUndoLine(Undo("B", RenameBand("B"), none), "u2", "u", "m")
        val shown = withPending(server, listOf(undoC, undoB))
        assertEquals(listOf("u2", "u1", "C", "B", "A"), shown.map { it.opId }, "newest first")
        assertEquals("A", undoTargets(shown).undo?.opId)
        // Once the server has an undo, its own line replaces the one shown at once.
        assertEquals(listOf("u2", "u1", "C", "B", "A"), withPending(listOf(undoC) + server, listOf(undoC, undoB)).map { it.opId })
    }
}
