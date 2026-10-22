package com.jacekgajek.splitart.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.await
import kotlin.js.Promise

// Web Share API where available (phones), otherwise the clipboard. Resolves to "shared", "copied" or "failed".
private fun shareOrCopy(text: String): Promise<JsString> = js(
    """(navigator.share
        ? navigator.share({ text: text }).then(() => 'shared', (e) => e && e.name === 'AbortError' ? 'shared' : 'failed')
        : navigator.clipboard.writeText(text).then(() => 'copied', () => 'failed'))""",
)

@Composable
actual fun rememberShare(): suspend (text: String) -> ShareResult = remember {
    { text ->
        when (runCatching { shareOrCopy(text).await<JsString>().toString() }.getOrNull()) {
            "shared" -> ShareResult.SHARED
            "copied" -> ShareResult.COPIED
            else -> ShareResult.FAILED
        }
    }
}

private fun copyToClipboard(text: String): Promise<JsBoolean> = js("navigator.clipboard.writeText(text).then(() => true, () => false)")

@Composable
actual fun rememberCopy(): suspend (text: String) -> Boolean = remember {
    { text -> runCatching { copyToClipboard(text).await<JsBoolean>().toBoolean() }.getOrDefault(false) }
}

// The browser asks the user once whether the site may read the clipboard.
private fun readClipboard(): Promise<JsString?> = js("navigator.clipboard.readText().then((t) => t, () => null)")

@Composable
actual fun rememberPaste(): suspend () -> String? = remember {
    { runCatching { readClipboard().await<JsString?>()?.toString() }.getOrNull() }
}
