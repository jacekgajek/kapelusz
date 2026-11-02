package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import kotlin.io.encoding.Base64
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

    @Composable
    override fun rememberOpen(mimeType: String): suspend (bytes: ByteArray, fileName: String) -> Boolean = remember {
        { bytes: ByteArray, name: String ->
            downloadBase64(Base64.encode(bytes), name, mimeType)
            true
        }
    }
}

private fun downloadBase64(base64: String, name: String, mime: String): Unit = js(
    """(() => {
          const binary = atob(base64);
          const bytes = new Uint8Array(binary.length);
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
          const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
          const a = document.createElement('a');
          a.href = url;
          a.download = name;
          document.body.appendChild(a);
          a.click();
          a.remove();
          setTimeout(() => URL.revokeObjectURL(url), 10000);
        })()""",
)
