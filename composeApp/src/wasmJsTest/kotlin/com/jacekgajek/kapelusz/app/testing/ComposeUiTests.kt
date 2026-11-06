@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.testing

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
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
 * Strings come over the network on the web, and the app's own work runs on the browser's loop: a real wait lets both
 * happen. The strings are English ones: Karma starts Chrome in `en-US` (karma.config.d/ui-tests.js). Up to [seconds],
 * which an end-to-end step, with a server round trip in it, may need more of than a smoke test.
 */
internal suspend fun ComposeUiTest.awaitText(text: String, seconds: Int = 10) {
    repeat(seconds * 20) {
        waitForIdle()
        if (onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
        withContext(Dispatchers.Default) { delay(50) }
    }
    throw AssertionError("no '$text' in: " + onRoot().printToString())
}
