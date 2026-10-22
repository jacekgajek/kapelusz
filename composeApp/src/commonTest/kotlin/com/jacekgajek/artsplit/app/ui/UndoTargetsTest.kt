package com.jacekgajek.artsplit.app.ui

import com.jacekgajek.artsplit.api.HistoryEntry
import com.jacekgajek.artsplit.app.ui.screens.HistoryLine
import com.jacekgajek.artsplit.app.ui.screens.undoTargets
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
}
