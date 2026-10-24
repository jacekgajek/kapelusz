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
import com.jacekgajek.kapelusz.app.ui.components.ZoomState
import com.jacekgajek.kapelusz.app.ui.components.zoomable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
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

/** An open PDF; one page renders at a time ([PdfRenderer] is not thread-safe). */
private class PdfDocument(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    val pageCount: Int get() = renderer.pageCount

    /** Page [index], [width] pixels wide, on white (PDFs assume paper). */
    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    /** Width ÷ height of page [index]. */
    suspend fun ratio(index: Int): Float = lock.withLock {
        withContext(Dispatchers.IO) { renderer.openPage(index).use { it.width.toFloat() / it.height } }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        /** [bytes] as a file the renderer can read (it needs one), under the cache's pdf folder. */
        fun open(context: Context, bytes: ByteArray): PdfDocument {
            val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
            val file = File(dir, "${bytes.contentHashCode()}.pdf")
            if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
            return PdfDocument(file)
        }
    }
}

/** Pages rendered this wide: sharp enough on a phone, zoomed in too, without huge bitmaps. */
private const val PAGE_WIDTH = 1400

@Composable
actual fun PdfPages(bytes: ByteArray, modifier: Modifier, onPage: (Int) -> Unit) {
    val context = LocalContext.current
    val document by produceState<PdfDocument?>(null, bytes) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDocument.open(context, bytes) }.getOrNull() }
    }
    val opened = document ?: return Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    DisposableEffect(opened) { onDispose { opened.close() } }
    val list = rememberLazyListState()
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect(onPage) }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(opened.pageCount) { index ->
            val ratio by produceState(1f / 1.414f, opened, index) { value = opened.ratio(index) }
            val page by produceState<Bitmap?>(null, opened, index) { value = opened.render(index, PAGE_WIDTH) }
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
                val bitmap = page
                if (bitmap == null) {
                    CircularProgressIndicator()
                } else {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(ratio).zoomable(remember { ZoomState() }), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

actual suspend fun pdfPagePng(bytes: ByteArray, page: Int): ByteArray? {
    val context = AndroidPlatform.context ?: return null
    return runCatching {
        PdfDocument.open(context, bytes).use { document ->
            val bitmap = document.render(page.coerceIn(0, document.pageCount - 1), PAGE_WIDTH)
            withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }
        }
    }.getOrNull()
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
