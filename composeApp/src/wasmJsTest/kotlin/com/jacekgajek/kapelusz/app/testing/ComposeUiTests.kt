@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.testing

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.js.Promise
import kotlinx.coroutines.await

/** Compose's test runner hands back a promise in the browser; the Kotest test waits for it. */
internal suspend fun composeUiTest(block: suspend ComposeUiTest.() -> Unit) {
    runComposeUiTest(block = block).unsafeCast<Promise<JsAny?>>().await()
}
