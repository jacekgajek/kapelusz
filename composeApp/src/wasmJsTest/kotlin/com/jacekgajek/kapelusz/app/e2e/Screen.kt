@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.e2e

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * What a user does on the screen, by the words on it. The test knows no composable, view model or operation. So a
 * refactor under the UI changes no test. A word is a string resource. So a change of wording changes no test either.
 *
 * Every step is recorded. A step that finds nothing fails with three things. The steps so far: how to get there by
 * hand. The words on screen: what the user sees instead. And last, the whole semantics tree.
 */
internal class Screen(private val ui: ComposeUiTest) {
    private val steps = mutableListOf<String>()

    suspend fun see(text: StringResource, seconds: Int = WAIT) = see(getString(text), seconds)

    /** Waits until [text] is on screen. It may be part of a longer text, or an icon's description. */
    suspend fun see(text: String, seconds: Int = WAIT) {
        steps += "see '$text'"
        val says = hasText(text, substring = true) or hasContentDescription(text, substring = true)
        await(seconds, "nothing says '$text'") { ui.onAllNodes(says).fetchSemanticsNodes().isNotEmpty() }
    }

    suspend fun shown(text: StringResource, seconds: Int = WAIT) = shown(getString(text), seconds)

    /** Whether [text] comes on screen within [seconds]. This does not fail; the test decides what to do. */
    suspend fun shown(text: String, seconds: Int = WAIT): Boolean {
        steps += "look for '$text'"
        val says = hasText(text, substring = true) or hasContentDescription(text, substring = true)
        repeat(seconds * 20) {
            ui.waitForIdle()
            if (ui.onAllNodes(says).fetchSemanticsNodes().isNotEmpty()) return true
            withContext(Dispatchers.Default) { delay(50) }
        }
        return false
    }

    /** Waits [seconds]. [why] goes into the steps. Only for what the screen cannot show, like a pending sync. */
    suspend fun wait(seconds: Int, why: String) {
        steps += "wait $seconds s: $why"
        withContext(Dispatchers.Default) { delay(seconds * 1000L) }
        ui.waitForIdle()
    }

    suspend fun tap(text: StringResource) = tap(getString(text))

    /**
     * Taps the one thing that says exactly [text] and can be tapped. The text may be an icon's description. A button,
     * a tab, a row, a choice. A heading with the same words does not count.
     */
    suspend fun tap(text: String) {
        steps += "tap '$text'"
        // Only something enabled. A tap on a disabled button does nothing, and the test would not know.
        val says = (hasText(text) or hasContentDescription(text)) and hasClickAction() and isEnabled()
        one(says, "nothing to tap says '$text'", "things say '$text', tap needs one").inView().performClick()
        ui.waitForIdle()
    }

    /** Taps the edit button of the row called [what]. The button is named "Edit: <row>". */
    suspend fun edit(what: StringResource) = tap("${getString(Res.string.edit)}: ${getString(what)}")

    suspend fun missing(text: StringResource) = missing(getString(text))

    /** Checks that nothing on screen says [text]. Waits a moment first, so a late screen is not an empty screen. */
    suspend fun missing(text: String) {
        steps += "check nothing says '$text'"
        wait(2, "the screen is complete")
        val says = hasText(text, substring = true) or hasContentDescription(text, substring = true)
        if (ui.onAllNodes(says).fetchSemanticsNodes().isNotEmpty()) fail("something says '$text', and nothing should")
    }

    /** A string resource with its arguments filled in, for a step. */
    suspend fun text(resource: StringResource, vararg args: Any): String = getString(resource, *args)

    suspend fun pick(text: StringResource) = pick(getString(text))

    /** Taps the item with [text] in the newest popup: an open menu, or an open dropdown. [text] may be part of the item. */
    suspend fun pick(text: String) {
        steps += "pick '$text'"
        val item = hasText(text, substring = true) and inNewestRoot()
        one(item, "no '$text' in the open menu", "items say '$text', pick needs one").inView().performClick()
        ui.waitForIdle()
    }

    suspend fun type(label: StringResource, value: String) = type(getString(label), value)

    /** Types [value] into the field with the label [label]. The old content is replaced. */
    suspend fun type(label: String, value: String) {
        steps += "type '$value' into '$label'"
        // Only a field. Not a choice or a heading with the same words.
        val field = hasSetTextAction() and hasText(label)
        one(field, "no field labelled '$label'", "fields are labelled '$label', typing needs one").inView().performTextReplacement(value)
        ui.waitForIdle()
    }

    /** Reads the one text on screen that matches [pattern] as a whole. For example an invite code. */
    suspend fun read(pattern: Regex): String {
        steps += "read '$pattern'"
        var found = emptyList<String>()
        await(WAIT, { if (found.isEmpty()) "nothing matches '$pattern'" else "${found.size} texts match '$pattern': $found" }) {
            found = texts(ui.onAllNodes(isRoot()).fetchSemanticsNodes()).filter { pattern.matches(it) }
            found.size == 1
        }
        return found.single()
    }

    /**
     * The one node that [matcher] finds. Waits until there is exactly one. While a screen opens or closes, a word can
     * be there twice for a moment. [none] is the failure message for no node. [several] is the message for more than one.
     */
    private suspend fun one(matcher: SemanticsMatcher, none: String, several: String): SemanticsNodeInteraction {
        var count = 0
        await(WAIT, { if (count == 0) none else "$count $several" }) {
            count = ui.onAllNodes(matcher).fetchSemanticsNodes().size
            count == 1
        }
        return ui.onAllNodes(matcher)[0]
    }

    /**
     * Scrolls the node into view when it is inside something that scrolls. A click lands at the node's centre. Below
     * the fold of a long dialog, that centre is outside the dialog, and the click would close it instead.
     */
    private fun SemanticsNodeInteraction.inView(): SemanticsNodeInteraction {
        runCatching { performScrollTo() }
        ui.waitForIdle()
        return this
    }

    /** A node in the root that opened last. A menu or a dropdown is a root of its own, after the app's root. */
    private fun inNewestRoot(): SemanticsMatcher = SemanticsMatcher("in the newest root") { node ->
        val newest = ui.onAllNodes(isRoot()).fetchSemanticsNodes().lastOrNull() ?: return@SemanticsMatcher false
        var up: SemanticsNode? = node
        while (up != null && up.id != newest.id) up = up.parent
        up != null
    }

    private suspend fun await(seconds: Int, problem: String, found: () -> Boolean) = await(seconds, { problem }, found)

    /**
     * Waits until [found] is true, up to [seconds]. This is a real wait. On the web the strings load over the network,
     * and the app works on the browser's loop. Some steps include a server round trip. A cold CI runner is slow.
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

    /** Every text on screen, in order. */
    private fun texts(roots: List<SemanticsNode>): List<String> {
        val all = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { all += it.text }
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return all
    }

    /** The words on screen, one per line. Texts, icon descriptions and the content of fields. */
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
