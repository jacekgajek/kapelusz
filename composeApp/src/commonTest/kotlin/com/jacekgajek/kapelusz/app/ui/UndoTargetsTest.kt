package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.app.ui.screens.HistoryLine
import com.jacekgajek.kapelusz.app.ui.screens.pendingUndoLine
import com.jacekgajek.kapelusz.app.ui.screens.stillPending
import com.jacekgajek.kapelusz.app.ui.screens.undoTargets
import com.jacekgajek.kapelusz.app.ui.screens.withPending
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.Undo
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val none = BandChange(BandPart(), BandPart())

private fun line(id: String, op: Operation) = HistoryLine(HistoryEntry("u", "m", 0, emptyList()), OpEnvelope(id, "c", 0, 0, op), id)

private fun change(id: String) = line(id, RenameBand(id))

private fun undo(id: String, target: HistoryLine) = line(id, Undo(target.opId, target.envelope!!.op, none))

class UndoTargetsTest :
    ExpectSpec({

        context("what undo and redo step to") {
            expect("a new change after an undo ends redo") {
                // A, B, undo B, C — shown newest first.
                val a = change("A")
                val b = change("B")
                val undoB = undo("uB", b)
                val c = change("C")
                val targets = undoTargets(listOf(c, undoB, b, a))
                targets.undo?.opId shouldBe "C"
                targets.redo.shouldBeNull()
                targets.undone shouldBe setOf("B")
            }

            expect("undo steps back and redo goes forward") {
                val a = change("A")
                val b = change("B")
                val undoB = undo("uB", b)
                val c = change("C")
                val undoC = undo("uC", c)
                val afterUndoC = undoTargets(listOf(undoC, c, undoB, b, a))
                withClue("B is already undone, so the next step back is A") { afterUndoC.undo?.opId shouldBe "A" }
                afterUndoC.redo?.opId shouldBe "uC"

                val redoC = undo("rC", undoC)
                val afterRedo = undoTargets(listOf(redoC, undoC, c, undoB, b, a))
                afterRedo.undo?.opId shouldBe "C"
                withClue("redo is not offered for a redo") { afterRedo.redo.shouldBeNull() }
                afterRedo.undone shouldBe setOf("uC", "B")
            }

            expect("changes that cannot be undone are stepped over") {
                val a = change("A")
                val joined = change("join")
                val targets = undoTargets(listOf(joined, a)) { it.opId != "join" }
                targets.undo?.opId shouldBe "A"
            }
        }

        context("undos not yet in the server's history") {
            expect("an undo shows at once so the next tap steps further back") {
                val a = change("A")
                val b = change("B")
                val c = change("C")
                val server = listOf(c, b, a)
                val undoC = pendingUndoLine(Undo("C", RenameBand("C"), none), "u1", "u", "m")
                val undoB = pendingUndoLine(Undo("B", RenameBand("B"), none), "u2", "u", "m")
                val shown = withPending(server, listOf(undoC, undoB))
                withClue("newest first") { shown.map { it.opId } shouldBe listOf("u2", "u1", "C", "B", "A") }
                undoTargets(shown).undo?.opId shouldBe "A"
                // Once the server has an undo, its own line replaces the one shown at once.
                withPending(listOf(undoC) + server, listOf(undoC, undoB)).map { it.opId } shouldBe listOf("u2", "u1", "C", "B", "A")
            }

            expect("a sent undo no longer needs its own line") {
                val undoC = pendingUndoLine(Undo("C", RenameBand("C"), none), "u1", "u", "m")
                val undoB = pendingUndoLine(Undo("B", RenameBand("B"), none), "u2", "u", "m")
                // u1 was sent (accepted, so in the server's history, or refused); u2 is still waiting to be sent.
                stillPending(listOf(undoC, undoB), unsent = setOf("u2")).map { it.opId } shouldBe listOf("u2")
            }
        }
    })
