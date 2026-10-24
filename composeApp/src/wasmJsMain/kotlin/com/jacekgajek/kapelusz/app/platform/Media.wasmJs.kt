package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise

/** For the session only: the browser has no file cache the app could use for this, and a reload starts over. */
actual object FileCache : ByteCache {
    private const val MAX_BYTES = 50L * 1024 * 1024
    private val files = LinkedHashMap<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = files.remove(key)?.also { files[key] = it }

    override suspend fun write(key: String, bytes: ByteArray) {
        files[key] = bytes
        var total = files.values.sumOf { it.size.toLong() }
        val keys = files.keys.iterator()
        while (total > MAX_BYTES && keys.hasNext()) {
            val oldest = keys.next()
            if (oldest == key) continue
            total -= files[oldest]?.size ?: 0
            keys.remove()
        }
    }
}

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

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean = remember {
    { bytes, _ ->
        val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val copied = copyImageJs(Base64.encode(bytes), if (png) "image/png" else "image/jpeg")
        runCatching { (copied.await<JsAny?>() as? JsBoolean)?.toBoolean() == true }.getOrDefault(false)
    }
}

/** The browser shows PDFs itself: they open there, as before. */
actual val pdfInViewer: Boolean = false

@Composable
actual fun PdfPages(bytes: ByteArray, modifier: Modifier, onPage: (Int) -> Unit) = Unit

actual suspend fun pdfPagePng(bytes: ByteArray, page: Int): ByteArray? = null

@Composable
actual fun rememberOpenPdf(): suspend (bytes: ByteArray, fileName: String) -> Boolean = remember { { _, _ -> false } }
