package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.components.UiMessage
import com.jacekgajek.kapelusz.app.ui.components.UndoItem
import com.jacekgajek.kapelusz.app.ui.components.UndoStack
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** The Undo snackbars' rules: at most three, each change released or undone once. */
class UndoStackTest :
    ExpectSpec({
        // Each test gets its own log of what the items were told.
        fun item(events: MutableList<String>, name: String) = UndoItem(
            UiMessage.Plain(name),
            undo = { events += "undo $name" },
            gone = { events += "gone $name" },
        )

        expect("a fourth pushes out the oldest which then syncs") {
            val events = mutableListOf<String>()
            val stack = UndoStack()
            val items = listOf("a", "b", "c", "d").map { item(events, it) }
            items.forEach(stack::add)
            stack.items.map { (it.message as UiMessage.Plain).text } shouldBe listOf("b", "c", "d")
            events shouldBe listOf("gone a")
        }

        expect("each change is undone or released once") {
            val events = mutableListOf<String>()
            val stack = UndoStack()
            val (a, b) = listOf("a", "b").map { item(events, it) }
            stack.add(a)
            stack.add(b)
            stack.undo(a)
            stack.dismiss(a) // already gone: nothing
            stack.undo(a)
            stack.dismissAll()
            stack.dismissAll()
            events shouldBe listOf("undo a", "gone b")
            stack.items.toList() shouldBe emptyList()
        }
    })
