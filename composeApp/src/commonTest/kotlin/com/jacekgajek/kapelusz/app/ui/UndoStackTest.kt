package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.components.UiMessage
import com.jacekgajek.kapelusz.app.ui.components.UndoItem
import com.jacekgajek.kapelusz.app.ui.components.UndoStack
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Undo snackbars' rules (ITERATION_5 §2): at most three, each change released or undone once. */
class UndoStackTest {
    private val events = mutableListOf<String>()

    private fun item(name: String) = UndoItem(UiMessage.Plain(name), undo = { events += "undo $name" }, gone = { events += "gone $name" })

    @Test
    fun aFourthPushesOutTheOldestWhichThenSyncs() {
        val stack = UndoStack()
        val items = listOf("a", "b", "c", "d").map(::item)
        items.forEach(stack::add)
        assertEquals(listOf("b", "c", "d"), stack.items.map { (it.message as UiMessage.Plain).text })
        assertEquals(listOf("gone a"), events)
    }

    @Test
    fun eachChangeIsUndoneOrReleasedOnce() {
        val stack = UndoStack()
        val (a, b) = listOf("a", "b").map(::item)
        stack.add(a)
        stack.add(b)
        stack.undo(a)
        stack.dismiss(a) // already gone: nothing
        stack.undo(a)
        stack.dismissAll()
        stack.dismissAll()
        assertEquals(listOf("undo a", "gone b"), events)
        assertEquals(emptyList(), stack.items.toList())
    }
}
