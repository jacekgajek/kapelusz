package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jacekgajek.kapelusz.app.data.Attachments
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.attachment_open_in_app
import com.jacekgajek.kapelusz.app.generated.attachment_rename
import com.jacekgajek.kapelusz.app.generated.attachment_unavailable
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.copy_failed
import com.jacekgajek.kapelusz.app.generated.image_copied
import com.jacekgajek.kapelusz.app.generated.image_copy
import com.jacekgajek.kapelusz.app.generated.remove
import com.jacekgajek.kapelusz.app.platform.PdfPages
import com.jacekgajek.kapelusz.app.platform.pdfPagePng
import com.jacekgajek.kapelusz.app.platform.rememberCopyImage
import com.jacekgajek.kapelusz.app.platform.rememberOpenPdf
import com.jacekgajek.kapelusz.app.ui.components.ZoomState
import com.jacekgajek.kapelusz.app.ui.components.resolved
import com.jacekgajek.kapelusz.app.ui.components.zoomable
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A file of an attachment, or why there is none: while loading, it is not there yet. */
private sealed interface Loaded {
    data object Loading : Loaded
    class File(val bytes: ByteArray) : Loaded
    data object Unavailable : Loaded
}

/** The file of [attachment], from the device or downloaded once ([com.jacekgajek.kapelusz.app.data.Attachments.bytes]). */
@Composable
private fun rememberFile(fileStore: Attachments, attachment: Attachment): Loaded {
    val loaded by produceState<Loaded>(Loaded.Loading, attachment.id) {
        // Not there yet (a bandmate's upload still going, or offline): asked again now and then while shown.
        while (true) {
            val bytes = fileStore.bytes(attachment.id)
            if (bytes != null) {
                value = Loaded.File(bytes)
                break
            }
            value = Loaded.Unavailable
            delay(RETRY_MS)
        }
    }
    return loaded
}

private const val RETRY_MS = 10_000L

/** An image attachment as a square thumbnail (ITERATION_5 §4.2); the image icon while it loads or when it cannot. */
@Composable
fun AttachmentThumbnail(fileStore: Attachments, attachment: Attachment, size: Dp, onClick: () -> Unit) {
    val name = attachment.fileName ?: attachment.id
    when (val file = rememberFile(fileStore, attachment)) {
        is Loaded.File -> ThumbnailBox(size, onClick, name) { BytesImage(file.bytes, attachment.id, ContentScale.Crop, Modifier.fillMaxSize()) }
        else -> ThumbnailBox(size, onClick, name) { Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A square for a thumbnail, rounded and tappable. */
@Composable
fun ThumbnailBox(size: Dp, onClick: (() -> Unit)?, name: String, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            // Read out by its file name, not as a bare "button".
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** An image from its bytes; [key] keeps the decoded image in memory between screens. */
@Composable
fun BytesImage(bytes: ByteArray, key: String, scale: ContentScale, modifier: Modifier) {
    val request = ImageRequest.Builder(LocalPlatformContext.current).data(bytes).memoryCacheKey(key).build()
    AsyncImage(request, contentDescription = null, contentScale = scale, modifier = modifier)
}

/**
 * The attachments of one cost, received money or event, full screen (ITERATION_5 §4.3): swipe between them, pinch to
 * zoom; copy on top, and rename and remove for who may edit. A PDF shows its pages where the platform renders them,
 * with "Open in app". Removing closes it first, so the Undo snackbar (or the confirmation) shows on the screen below.
 */
@Composable
fun AttachmentViewer(
    fileStore: Attachments,
    attachments: List<Attachment>,
    start: Int,
    onRename: ((Attachment) -> Unit)?,
    onRemove: ((Attachment) -> Unit)?,
    onOpenElsewhere: (Attachment) -> Unit,
    onDismiss: () -> Unit,
) {
    resolved(attachments.takeIf { it.isNotEmpty() }, onDismiss) ?: return
    val pager = rememberPagerState(start.coerceIn(0, attachments.lastIndex)) { attachments.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copyImage = rememberCopyImage()
    val openPdf = rememberOpenPdf()
    // The PDF page mostly in view, per attachment: copy copies that page.
    val pdfPages = remember { mutableStateMapOf<String, Int>() }
    val files = remember { mutableStateMapOf<String, ByteArray>() }
    val copied = stringResource(Res.string.image_copied)
    val failed = stringResource(Res.string.copy_failed)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { attachments[it].id }) { page ->
                val attachment = attachments[page]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (val file = rememberFile(fileStore, attachment)) {
                        Loaded.Loading -> CircularProgressIndicator()

                        Loaded.Unavailable -> Text(stringResource(Res.string.attachment_unavailable), color = Color.White, modifier = Modifier.padding(32.dp))

                        is Loaded.File -> {
                            LaunchedEffect(file) { files[attachment.id] = file.bytes }
                            if (attachment.kind == AttachmentKind.PDF) {
                                // Below the top bar, so the first page's top can be seen.
                                PdfPages(file.bytes, Modifier.fillMaxSize().padding(top = 56.dp).statusBarsPadding()) { pdfPages[attachment.id] = it }
                            } else {
                                BytesImage(file.bytes, attachment.id, ContentScale.Fit, Modifier.fillMaxSize().zoomable(remember { ZoomState() }))
                            }
                        }
                    }
                }
            }
            val current = attachments[pager.currentPage.coerceIn(0, attachments.lastIndex)]
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(Res.string.close), tint = Color.White) }
                Text(
                    "${pager.currentPage + 1} / ${attachments.size} · ${current.fileName ?: current.id}",
                    Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pdf = current.kind == AttachmentKind.PDF
                if (pdf) {
                    IconButton(onClick = {
                        val bytes = files[current.id]
                        scope.launch { if (bytes == null || !openPdf(bytes, current.fileName ?: "${current.id}.pdf")) onOpenElsewhere(current) }
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(Res.string.attachment_open_in_app), tint = Color.White) }
                }
                IconButton(onClick = {
                    val bytes = files[current.id] ?: return@IconButton
                    // Started at once, still within the tap: browsers only let a tap write images to the clipboard.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        val ok = if (pdf) {
                            pdfPagePng(bytes, pdfPages[current.id] ?: 0)?.let { copyImage(it, "${current.fileName ?: current.id}.png") } ?: false
                        } else {
                            copyImage(bytes, current.fileName ?: "${current.id}.jpg")
                        }
                        snackbar.showSnackbar(if (ok) copied else failed)
                    }
                }) { Icon(Icons.Default.ContentCopy, stringResource(Res.string.image_copy), tint = Color.White) }
                if (onRename != null) {
                    IconButton(onClick = { onRename(current) }) { Icon(Icons.Default.Edit, stringResource(Res.string.attachment_rename), tint = Color.White) }
                }
                if (onRemove != null) {
                    IconButton(onClick = {
                        onDismiss()
                        onRemove(current)
                    }) { Icon(Icons.Default.Delete, stringResource(Res.string.remove), tint = Color.White) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
