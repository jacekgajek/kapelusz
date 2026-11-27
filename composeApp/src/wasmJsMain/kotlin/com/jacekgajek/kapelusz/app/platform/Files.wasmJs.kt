@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.jacekgajek.kapelusz.app.jsObject
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.catching
import com.jacekgajek.kapelusz.model.AttachmentKind
import kotlin.coroutines.resume
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.toJsNumber
import kotlin.js.unsafeCast
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.ElementCreationOptions
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.ImageBitmap
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.FileReader

/** A narrowed FileReader view used only after calling readAsDataURL(). */
private external interface DataUrlFileReader : JsAny {
    val result: String?
}

// Opens the browser's file chooser (phones offer the camera there too) and prepares the chosen file: a PDF as it is,
// an image re-encoded through a canvas (orientation applied, longest edge 1600 px, JPEG 0.8), which drops EXIF/GPS.
// Several files can be chosen; returns one result each, in order, or null when the user cancels. File content is read
// only after the file chooser returns and stays in Kotlin as a base64 string from then on.
// Process sequentially: decoding several full-resolution photos at once would use substantially more memory.
private suspend fun pickAndPrepare(): List<PickResult>? = chooseFiles()?.map { file -> prepare(file) }

private suspend fun chooseFiles(): List<File>? = suspendCancellableCoroutine { continuation ->
    val input = document.createElement("input", ElementCreationOptions(null)).unsafeCast<HTMLInputElement>()
    input.type = "file"
    input.accept = "image/*,application/pdf"
    input.multiple = true
    input.style.display = "none"
    document.body?.appendChild(input)

    fun finish(files: List<File>?) {
        input.onchange = null
        input.oncancel = null
        input.remove()
        if (continuation.isActive) continuation.resume(files)
    }

    continuation.invokeOnCancellation { input.remove() }
    input.oncancel = { finish(null) }
    input.onchange = {
        val selected = input.files
        finish(
            selected?.let { fileList ->
                (0 until fileList.length).mapNotNull { fileList.item(it) }
            }?.takeIf { it.isNotEmpty() },
        )
    }
    // Keep this synchronous with the original click event, since browsers block delayed file-picker requests.
    input.click()
}

private suspend fun prepare(file: File): PickResult = catching {
    when {
        file.type == "application/pdf" || file.name.endsWith(".pdf", ignoreCase = true) -> handlePdf(file)
        file.type.startsWith("image/") -> handleImage(file)
        else -> PickResult.Unsupported
    }
}.getOrElse { PickResult.Failed }

private suspend fun handleImage(file: File): PickResult = catching {
    createOrientedBitmap(file).await<ImageBitmap>()
}.map { bitmap ->
    bitmap.use { decoded ->
        val scale = minOf(1.0, 1600.0 / maxOf(decoded.width, decoded.height))
        val canvas = document.createElement("canvas", ElementCreationOptions(null)).unsafeCast<HTMLCanvasElement>()
        canvas.width = maxOf(1, (decoded.width * scale).toInt())
        canvas.height = maxOf(1, (decoded.height * scale).toInt())
        val context = canvasContext(canvas) ?: error("Canvas 2D is unavailable")
        context.drawImage(decoded, 0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
        canvas.toJpeg()
    } ?: error("Failed to build JPEG")
}.map { blob ->
    val size = blob.size.toDouble().toLong()
    if (size > AttachmentKind.IMAGE.maxBytes) {
        PickResult.TooLarge
    } else {
        val fileName = file.name.substringBeforeLast('.', file.name) + ".jpg"
        PickResult.Picked(PreparedFile(AttachmentKind.IMAGE, fileName, size, blob.readAsBase64()))
    }
}.getOrElse { PickResult.Unsupported }

private suspend fun handlePdf(file: File): PickResult = file.size.toDouble().toLong().let { size ->
    if (size > AttachmentKind.PDF.maxBytes) {
        PickResult.TooLarge
    } else {
        PickResult.Picked(PreparedFile(AttachmentKind.PDF, file.name, size, file.readAsBase64()))
    }
}

// The generated enum bindings omit "from-image", which is required to apply EXIF orientation before drawing.
@Suppress("UNUSED_PARAMETER") // language=javascript
private fun createOrientedBitmap(file: File): Promise<ImageBitmap> = js("createImageBitmap(file, { imageOrientation: 'from-image' })")

private suspend fun Blob.readAsBase64(): String = suspendCancellableCoroutine { continuation ->
    val reader = FileReader()
    reader.onload = {
        val dataUrl = reader.unsafeCast<DataUrlFileReader>().result
        if (continuation.isActive) continuation.resume(dataUrl?.substringAfter(',', "").orEmpty())
    }
    reader.onerror = {
        if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("File read failed")))
    }
    continuation.invokeOnCancellation { reader.abort() }
    reader.readAsDataURL(this)
}

private suspend fun ImageBitmap.use(block: suspend (ImageBitmap) -> Blob?): Blob? = try {
    block(this)
} finally {
    close()
}

private suspend fun HTMLCanvasElement.toJpeg(): Blob? = suspendCancellableCoroutine { continuation ->
    toBlob(
        { blob -> if (continuation.isActive) continuation.resume(blob) },
        "image/jpeg",
        0.8.toJsNumber(),
    )
}

// The generated DOM binding exposes getContext() as a broad RenderingContext union.
@Suppress("UNUSED_PARAMETER") // language=javascript
private fun canvasContext(canvas: HTMLCanvasElement): CanvasRenderingContext2D? = js("canvas.getContext('2d')")

@Composable
internal fun rememberWebAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    return remember {
        object : AttachmentPicker {
            override fun pickFile() {
                // Undispatched: the chooser must open while the click is still being handled.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runSuspendCatching { pickAndPrepare() }
                        .onSuccess { it?.let(callback.value) }
                        .onFailure { callback.value(listOf(PickResult.Failed)) }
                }
            }

            override val takePhoto: (() -> Unit)? = null
        }
    }
}

internal suspend fun putFile(url: String, headers: Map<String, String>, base64: String): Int = catching {
    val bytes = decodeBase64(base64)
    val requestHeaders = Headers(jsObject<JsAny>()).apply { headers.forEach { (key, value) -> set(key, value) } }
    val request = jsObject<RequestInit>().apply {
        method = "PUT"
        this.headers = requestHeaders
        body = bytes
    }
    window.fetch(url, request).await<Response>().status.toInt()
}.getOrElse { -1 }

private fun openBlankWindow() = window.open("about:blank", "_blank", "")

// Without the blank window (a popup blocker took it), opening the URL directly is the best remaining option.
private fun navigateWindow(target: org.w3c.dom.Window?, url: String) = target
    ?.takeUnless { it.closed }
    ?.let { it.location.href = url }
    ?: window.open(url, "_blank", "")

private fun closeWindow(target: org.w3c.dom.Window?) = target?.close()

@Composable
internal fun rememberWebUrlOpener(): (resolve: suspend () -> String?) -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        { resolve ->
            val target = openBlankWindow()
            scope.launch {
                val url = runSuspendCatching { resolve() }.getOrNull()
                if (url == null) closeWindow(target) else navigateWindow(target, url)
            }
        }
    }
}
