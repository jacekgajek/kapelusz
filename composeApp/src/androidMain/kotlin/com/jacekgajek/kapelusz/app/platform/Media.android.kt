package com.jacekgajek.kapelusz.app.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

actual object FileCache : ByteCache {
    /** Above this the files used longest ago go (ITERATION_5 §4.2). */
    private const val MAX_BYTES = 100L * 1024 * 1024

    private fun dir(): File? = AndroidPlatform.context?.let { File(it.cacheDir, "attachments").apply { mkdirs() } }

    private fun name(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = dir()?.let { File(it, name(key)) }?.takeIf { it.isFile } ?: return@withContext null
        // Used now: the last to go when room is needed.
        file.setLastModified(System.currentTimeMillis())
        runCatching { file.readBytes() }.getOrNull()
    }

    override suspend fun write(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val dir = dir() ?: return@withContext
        val part = File(dir, name(key) + ".part")
        part.writeBytes(bytes)
        // Whole or not at all: a crash halfway leaves a .part, never a broken file under the key.
        part.renameTo(File(dir, name(key)))
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}

/** [bytes] in a file in the cache's [folder] under [fileName] (made safe, with [extension]), for another app to read. */
private fun shareable(context: Context, folder: String, fileName: String, extension: String, bytes: ByteArray): android.net.Uri {
    val dir = File(context.cacheDir, folder).apply { mkdirs() }
    // Only the latest: earlier ones were for an app that has read them by now.
    dir.listFiles()?.forEach { it.delete() }
    val base = fileName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifBlank { "file" }
    val file = File(dir, "$base.$extension")
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

private fun isPng(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()

@Composable
actual fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { bytes, fileName ->
            runCatching {
                val uri = withContext(Dispatchers.IO) { shareable(context, "clip", fileName, if (isPng(bytes)) "png" else "jpg", bytes) }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // The image itself (a content URI apps paste as a picture), not a link to it.
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, fileName, uri))
                true
            }.getOrDefault(false)
        }
    }
}

actual val pdfInViewer: Boolean = true

/**
 * An open PDF; one page renders at a time ([PdfRenderer] is not thread-safe). Closed through the same lock, after any
 * page being rendered, and in a scope of its own: the screen that opened it may already be gone. Closed, it renders
 * nothing (null) instead of failing.
 */
private class PdfDocument(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    /** Page [index], [width] pixels wide, on white (PDFs assume paper). */
    suspend fun render(index: Int, width: Int): Bitmap? = use { renderer ->
        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** Width ÷ height of page [index]. */
    suspend fun ratio(index: Int): Float? = use { renderer -> renderer.openPage(index).use { it.width.toFloat() / it.height } }

    private suspend fun <T> use(block: (PdfRenderer) -> T): T? = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock { if (closed) null else runCatching { block(renderer) }.getOrNull() }
    }

    fun close() {
        closer.launch {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    companion object {
        private val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Copies kept for the renderer (it needs a file); the latest few, so the cache does not grow with every PDF. */
        private const val KEPT = 3

        /** [bytes] as a file the renderer can read, under the cache's pdf folder; written whole before it is used. */
        suspend fun open(context: Context, bytes: ByteArray): PdfDocument? = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
                val file = File(dir, "${bytes.contentHashCode()}-${bytes.size}.pdf")
                if (!file.isFile) {
                    val part = File(dir, "${file.name}.${System.nanoTime()}.part")
                    part.writeBytes(bytes)
                    part.renameTo(file)
                }
                file.setLastModified(System.currentTimeMillis())
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEPT)?.forEach { it.delete() }
                PdfDocument(file)
            }.getOrNull()
        }
    }
}

/** Pages rendered this wide: sharp enough on a phone, zoomed in too, without huge bitmaps. */
private const val PAGE_WIDTH = 1400

@Composable
actual fun PdfPages(bytes: ByteArray, modifier: Modifier, onPage: (Int) -> Unit) {
    val context = LocalContext.current
    val document by produceState<PdfDocument?>(null, bytes) { value = PdfDocument.open(context, bytes) }
    val opened = document ?: return Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    DisposableEffect(opened) { onDispose { opened.close() } }
    val list = rememberLazyListState()
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect(onPage) }
    // The whole document zooms, as in a PDF reader: a pinch works wherever the fingers are, also across two pages.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.clipToBounds()
            .pointerInput(list) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offsetX = 0f
                    } else {
                        // In on the tapped point: it stays where it was.
                        list.dispatchRawDelta(tap.y * (1 - 1 / DOUBLE_TAP_SCALE))
                        scale = DOUBLE_TAP_SCALE
                        offsetX = ((size.width / 2f - tap.x) * (scale - 1)).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                    }
                })
            }
            .pointerInput(list) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        // Seen before the list: two fingers, or one while zoomed in, are this zoom's; one finger at
                        // the whole size is left to the list's scrolling and the pages' swiping.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (fingers > 0 && centroid.isSpecified && (fingers >= 2 || scale > 1f)) {
                            val zoom = event.calculateZoom().takeIf { it.isFinite() } ?: 1f
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, MAX_PDF_SCALE)
                            // Vertically the list scrolls (in unscaled pixels), keeping the point under the fingers.
                            list.dispatchRawDelta(centroid.y * (1 / scale - 1 / next) - pan.y / next)
                            val maxX = size.width * (next - 1) / 2
                            offsetX = (offsetX + (centroid.x - size.width / 2f - offsetX) * (1 - next / scale) + pan.x).coerceIn(-maxX, maxX)
                            scale = next
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                // From the top: the list's own scrolling moves through the document.
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(opened.pageCount) { index ->
                val ratio by produceState(1f / 1.414f, opened, index) { opened.ratio(index)?.let { value = it } }
                val page by produceState<Bitmap?>(null, opened, index) { value = opened.render(index, PAGE_WIDTH) }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(ratio).background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = page
                    if (bitmap == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}

private const val MAX_PDF_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

actual suspend fun pdfPagePng(bytes: ByteArray, page: Int): ByteArray? {
    val context = AndroidPlatform.context ?: return null
    val document = PdfDocument.open(context, bytes) ?: return null
    return try {
        val bitmap = document.render(page.coerceIn(0, document.pageCount - 1), PAGE_WIDTH) ?: return null
        withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
    } finally {
        document.close()
    }
}

@Composable
actual fun rememberOpenPdf(): suspend (bytes: ByteArray, fileName: String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { bytes, fileName ->
            runCatching {
                val uri = withContext(Dispatchers.IO) { shareable(context, "open", fileName, "pdf", bytes) }
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(view)
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                }
            }.getOrDefault(false)
        }
    }
}
