@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.e2e

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * What a user does on the screen, by the words on it. The test knows no composable, view model or operation, so a
 * refactor underneath changes no test. A word is a string resource, so a change of wording changes no test either.
 *
 * Every step is recorded. A step that finds nothing fails with: the steps so far (how to get there by hand), the words
 * on screen at that moment (what the user sees instead), and last the whole semantics tree.
 */
internal class Screen(private val ui: ComposeUiTest) {
    private val steps = mutableListOf<String>()

    suspend fun see(text: StringResource, seconds: Int = WAIT) = see(getString(text), seconds)

    /** Waits until [text] is on screen, as a whole or as part of a longer text. */
    suspend fun see(text: String, seconds: Int = WAIT) {
        steps += "see '$text'"
        await(seconds, "nothing says '$text'") { ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    suspend fun tap(text: StringResource) = tap(getString(text))

    /** Taps the one thing that says exactly [text], as text or as an icon's description: a button, a tab, a row, a choice. */
    suspend fun tap(text: String) {
        steps += "tap '$text'"
        one(hasText(text) or hasContentDescription(text), "nothing to tap says '$text'", "things say '$text', tap needs one").performClick()
        ui.waitForIdle()
    }

    suspend fun type(label: StringResource, value: String) = type(getString(label), value)

    /** Types [value] into the field labelled [label]. The old content is replaced. */
    suspend fun type(label: String, value: String) {
        steps += "type '$value' into '$label'"
        // The field itself, not a choice or a heading with the same words.
        val field = hasSetTextAction() and hasText(label)
        one(field, "no field labelled '$label'", "fields are labelled '$label', typing needs one").performTextReplacement(value)
        ui.waitForIdle()
    }

    /**
     * The one node that [matcher] finds. Waits until there is exactly one: while a screen opens or closes, a word can be
     * there twice for a moment. [none] and [several] are the failure messages for zero and for more than one.
     */
    private suspend fun one(matcher: SemanticsMatcher, none: String, several: String): SemanticsNodeInteraction {
        var count = 0
        await(WAIT, { if (count == 0) none else "$count $several" }) {
            count = ui.onAllNodes(matcher).fetchSemanticsNodes().size
            count == 1
        }
        return ui.onAllNodes(matcher)[0]
    }

    private suspend fun await(seconds: Int, problem: String, found: () -> Boolean) = await(seconds, { problem }, found)

    /**
     * Waits until [found], up to [seconds]. On the web the strings load over the network and the app works on the
     * browser's loop, so this is a real wait. Some steps include a server round trip, and a cold CI runner is slow.
     */
    private suspend fun await(seconds: Int, problem: () -> String, found: () -> Boolean) {
        repeat(seconds * 20) {
            ui.waitForIdle()
            if (found()) return
            withContext(Dispatchers.Default) { delay(50) }
        }
        fail("${problem()} after $seconds s")
    }

    private fun fail(problem: String): Nothing {
        val trail = steps.mapIndexed { i, step -> "  ${i + 1}. $step" }.joinToString("\n")
        val roots = ui.onAllNodes(isRoot())
        val rerun = "./gradlew :composeApp:wasmJsBrowserTest -Pe2e -Pe2e.spec=$currentSpec"
        throw AssertionError(
            "$problem\n\nSteps (the last one failed):\n$trail\n\nOn screen:\n${words(roots.fetchSemanticsNodes())}\n\n" +
                "Rerun this spec alone: $rerun\n\nThe tree:\n${roots.printToString(maxDepth = Int.MAX_VALUE)}",
        )
    }

    /** The words on screen, one per line: texts, icon descriptions, and the content of fields. */
    private fun words(roots: List<SemanticsNode>): String {
        val lines = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ")
            val typed = node.config.getOrNull(SemanticsProperties.EditableText)?.text
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
            val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
            val what = listOfNotNull(
                text?.let { if (typed != null) "field '$it' = '$typed'" else "'$it'" },
                description?.takeIf { it != text }?.let { "icon '$it'" },
            )
            if (what.isNotEmpty()) lines += "  " + what.joinToString(", ") + (role?.let { " ($it)" } ?: "")
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return lines.joinToString("\n")
    }
}

private const val WAIT = 30
