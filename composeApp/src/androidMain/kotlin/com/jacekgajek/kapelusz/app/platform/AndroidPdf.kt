package com.jacekgajek.kapelusz.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer as SystemPdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.ui.components.PdfZoom
import com.jacekgajek.kapelusz.app.ui.components.zoomGestures
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** PDFs through Android's renderer, in the app's cache. */
object AndroidPdf : PdfRenderer {
    @Composable
    override fun Pages(bytes: ByteArray, modifier: Modifier, onPage: (Int) -> Unit) {
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

    @Composable
    override fun rememberPagePng(): suspend (bytes: ByteArray, page: Int) -> ByteArray? {
        val context = LocalContext.current.applicationContext
        return remember(context) { { bytes, page -> pagePng(context, bytes, page) } }
    }

    @Composable
    override fun rememberOpen(): suspend (bytes: ByteArray, fileName: String) -> Boolean {
        val context = LocalContext.current
        return remember(context) {
            { bytes, fileName ->
                runCatching {
                    val uri = withContext(Dispatchers.IO) { shareable(context, "open", fileName, "pdf", bytes) }
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(view)
                        true
                    } catch (ignored: ActivityNotFoundException) {
                        false
                    }
                }.getOrDefault(false)
            }
        }
    }
}

/** Pages rendered this wide: sharp enough on a phone, zoomed in too, without huge bitmaps. */
private const val PAGE_WIDTH = 1400

private suspend fun pagePng(context: Context, bytes: ByteArray, page: Int): ByteArray? {
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

/**
 * An open PDF; one page renders at a time (the system's renderer is not thread-safe). Closed through the same lock, after any
 * page being rendered, and in a scope of its own: the screen that opened it may already be gone. Closed, it renders
 * nothing (null) instead of failing.
 */
private class PdfDocument(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = SystemPdfRenderer(descriptor)
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    /** Page [index], [width] pixels wide, on white (PDFs assume paper). */
    suspend fun render(index: Int, width: Int): Bitmap? = use { renderer ->
        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, SystemPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** Width ÷ height of page [index]. */
    suspend fun ratio(index: Int): Float? = use { renderer -> renderer.openPage(index).use { it.width.toFloat() / it.height } }

    private suspend fun <T> use(block: (SystemPdfRenderer) -> T): T? = withContext(NonCancellable + Dispatchers.IO) {
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
