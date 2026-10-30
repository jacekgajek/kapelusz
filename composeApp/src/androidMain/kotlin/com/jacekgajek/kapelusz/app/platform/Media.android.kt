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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntSize
import com.jacekgajek.kapelusz.app.ui.components.EDGE_SLACK
import com.jacekgajek.kapelusz.app.ui.components.ZoomTarget
import com.jacekgajek.kapelusz.app.ui.components.zoomGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.fillMaxSize
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

    actual override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = dir()?.let { File(it, name(key)) }?.takeIf { it.isFile } ?: return@withContext null
        // Used now: the last to go when room is needed.
        file.setLastModified(System.currentTimeMillis())
        runCatching { file.readBytes() }.getOrNull()
    }

    actual override suspend fun write(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
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
    val zoom = remember(list) { PdfZoom { delta -> list.dispatchRawDelta(delta) } }
    // Seen before the list (the initial pass): two fingers, or one while zoomed in, are this zoom's; one finger at
    // the whole size is left to the list's scrolling and the pages' swiping.
    Box(
        modifier.clipToBounds().zoomGestures(zoom, PointerEventPass.Initial),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offsetX
                translationY = zoom.offsetY
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

/**
 * The PDF view's zoom (ITERATION_5 §4.3): the page list is scaled from its top, so up and down are the list's own
 * scrolling (in its unscaled pixels, through [scrollList], which returns what it could take) plus [offsetY] for what
 * the list cannot reach: zoomed in, the list ends while the bottom of the document is still below the screen (a
 * single page cannot scroll at all), so the view itself moves the rest of the way. Sideways is [offsetX].
 */
internal class PdfZoom(private val scrollList: (Float) -> Float) : ZoomTarget {
    override var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set

    /** How far the scaled view is moved up past the list's end; 0 or less. */
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** Moves the document by [dy] screen pixels (a finger moving down: positive) within the [height] shown. */
    fun moveVertically(dy: Float, height: Float) {
        val maxY = height * (scale - 1)
        if (dy < 0) {
            // Further down the document: the list first, then the view for what the list could not take.
            val taken = scrollList(-dy / scale)
            offsetY = (offsetY + dy + taken * scale).coerceIn(-maxY, 0f)
        } else {
            // Back up: the view first, then the list.
            val back = minOf(dy, -offsetY)
            offsetY += back
            val rest = dy - back
            if (rest > 0f) scrollList(-rest / scale)
        }
    }

    /** Zooms by [zoom] around [centroid] (it stays under the fingers) and moves by [pan], in a view of [size]. */
    override fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize) {
        val old = scale
        val next = (old * zoom).coerceIn(1f, MAX_PDF_SCALE)
        // The document point under the centroid stays there: the view's own move scales along, the list makes up the rest.
        val nextOffsetY = offsetY * (next / old)
        val listDelta = (centroid.y - offsetY) / old - (centroid.y - nextOffsetY) / next
        scale = next
        offsetY = nextOffsetY
        val taken = scrollList(listDelta)
        offsetY = (offsetY - (listDelta - taken) * next).coerceIn(-size.height * (next - 1), 0f)
        moveVertically(pan.y, size.height.toFloat())
        val maxX = size.width * (next - 1) / 2
        offsetX = (offsetX + (centroid.x - size.width / 2f - offsetX) * (1 - next / old) + pan.x).coerceIn(-maxX, maxX)
    }

    override fun atEdge(dx: Float, size: IntSize): Boolean {
        val maxX = size.width * (scale - 1) / 2
        return if (dx > 0) offsetX >= maxX - EDGE_SLACK else offsetX <= -maxX + EDGE_SLACK
    }

    /** A double tap: in on [tap], or back to the whole width (at the same place in the document). */
    override fun toggle(tap: Offset, size: IntSize) {
        if (scale > 1f) {
            // The part above the screen that the view (not the list) was hiding: given back to the list.
            scrollList(-offsetY / scale)
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else {
            transform(DOUBLE_TAP_SCALE, Offset.Zero, tap, size)
        }
    }

    private companion object {
        const val MAX_PDF_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

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
