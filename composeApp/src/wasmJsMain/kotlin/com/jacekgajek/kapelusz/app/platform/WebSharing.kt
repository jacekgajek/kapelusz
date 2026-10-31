package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import kotlin.js.Promise
import kotlinx.coroutines.await

// Web Share API where available (phones), otherwise the clipboard. Resolves to "shared", "copied" or "failed".
private fun shareOrCopy(text: String): Promise<JsString> = js(
    """(navigator.share
        ? navigator.share({ text: text }).then(() => 'shared', (e) => e && e.name === 'AbortError' ? 'shared' : 'failed')
        : navigator.clipboard.writeText(text).then(() => 'copied', () => 'failed'))""",
)

/** The Web Share API where the browser has it (phones), the clipboard otherwise. */
object WebSharing : Sharing {
    @Composable
    override fun rememberShare(): suspend (text: String) -> ShareResult = remember {
        { text ->
            when (runSuspendCatching { shareOrCopy(text).await<JsString>().toString() }.getOrNull()) {
                "shared" -> ShareResult.SHARED
                "copied" -> ShareResult.COPIED
                else -> ShareResult.FAILED
            }
        }
    }
}
