package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.await

private fun copyToClipboard(text: String): Promise<JsBoolean> = js("navigator.clipboard.writeText(text).then(() => true, () => false)")

// The browser asks the user once whether the site may read the clipboard.
private fun readClipboard(): Promise<JsString?> = js("navigator.clipboard.readText().then((t) => t, () => null)")

/**
 * Writes the image to the clipboard as a PNG, the one image type browsers accept there (a JPEG is redrawn first).
 * The clipboard is asked at once with a promise of the image, so it still counts as the tap (Safari requires that).
 */
private fun copyImageJs(base64: String, type: String): Promise<JsAny?> = js(
    """(() => {
        const png = (async () => {
            const raw = atob(base64);
            const bytes = new Uint8Array(raw.length);
            for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
            const blob = new Blob([bytes], { type });
            if (type === 'image/png') return blob;
            const bitmap = await createImageBitmap(blob);
            const canvas = document.createElement('canvas');
            canvas.width = bitmap.width;
            canvas.height = bitmap.height;
            canvas.getContext('2d').drawImage(bitmap, 0, 0);
            return await new Promise((resolve, reject) => canvas.toBlob((b) => b ? resolve(b) : reject(new Error('png')), 'image/png'));
        })();
        try {
            return navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]).then(() => true, () => false);
        } catch (e) {
            return Promise.resolve(false);
        }
    })()""",
)

/** The browser's clipboard (the Clipboard API). */
object WebClipboard : Clipboard {
    @Composable
    override fun rememberCopy(): suspend (text: String) -> Boolean = remember {
        { text -> runSuspendCatching { copyToClipboard(text).await<JsBoolean>().toBoolean() }.getOrDefault(false) }
    }

    @Composable
    override fun rememberPaste(): suspend () -> String? = remember {
        { runSuspendCatching { readClipboard().await<JsString?>()?.toString() }.getOrNull() }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Composable
    override fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean = remember {
        { bytes, _ ->
            val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
            val copied = copyImageJs(Base64.encode(bytes), if (png) "image/png" else "image/jpeg")
            runCatching { (copied.await<JsAny?>() as? JsBoolean)?.toBoolean() == true }.getOrDefault(false)
        }
    }
}
