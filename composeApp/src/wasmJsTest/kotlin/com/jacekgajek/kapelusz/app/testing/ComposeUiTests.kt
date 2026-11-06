@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.testing

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlin.js.Promise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Compose's test runner hands back a promise in the browser; the Kotest test waits for it. */
internal suspend fun composeUiTest(block: suspend ComposeUiTest.() -> Unit) {
    runComposeUiTest(block = block).unsafeCast<Promise<JsAny?>>().await()
}

/**
 * Waits until [text] is on screen, up to [seconds]. This is a real wait. On the web the strings load over the network,
 * and the app works on the browser's loop. The strings are English. Karma starts Chrome in `en-US`
 * (karma.config.d/ui-tests.js). An end-to-end step with a server round trip needs more time than a smoke test.
 */
internal suspend fun ComposeUiTest.awaitText(text: String, seconds: Int = 10) {
    repeat(seconds * 20) {
        waitForIdle()
        if (onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
        withContext(Dispatchers.Default) { delay(50) }
    }
    throw AssertionError("no '$text' in: " + screen())
}

/** Everything on screen, for a failure message. An open dialog is a second root next to the app's root. */
internal fun ComposeUiTest.screen(): String = onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE)
