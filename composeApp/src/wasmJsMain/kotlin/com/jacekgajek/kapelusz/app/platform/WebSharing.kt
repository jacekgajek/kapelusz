@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import kotlin.io.encoding.Base64
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.ElementCreationOptions
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL

// Web Share API where available (phones), otherwise the clipboard. Resolves to "shared", "copied" or "failed".
@Suppress("unused")
// language=javascript
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

    @Composable
    override fun rememberOpen(mimeType: String): suspend (bytes: ByteArray, fileName: String) -> Boolean = remember {
        { bytes: ByteArray, name: String ->
            downloadBase64(Base64.encode(bytes), name, mimeType)
            true
        }
    }
}

private fun downloadBase64(base64: String, name: String, mime: String) {
    val url = URL.createObjectURL(byteBlob(base64, mime))
    val anchor = document.createElement("a", ElementCreationOptions(null)).unsafeCast<HTMLAnchorElement>()
    anchor.href = url
    anchor.download = name
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout({
        URL.revokeObjectURL(url)
        null
    }, 10_000)
}
