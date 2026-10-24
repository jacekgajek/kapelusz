package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Attachment files kept on the device by attachment id (ITERATION_5 §4.2): on Android in the app's cache, the oldest
 * dropped above about 100 MB (and by the system when it needs room); on the web only for the session.
 */
expect object FileCache : ByteCache

/** Files by key; see [FileCache]. */
interface ByteCache {
    suspend fun read(key: String): ByteArray?

    suspend fun write(key: String, bytes: ByteArray)
}

/** Puts the image itself on the clipboard (a JPEG or PNG file's bytes); false where the platform cannot. */
@Composable
expect fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean

/** Whether PDFs show in the app's viewer (Android renders them); elsewhere they open in the browser. */
expect val pdfInViewer: Boolean

/** The pages of a PDF, one under another, each zoomable; [onPage] gets the page mostly in view. */
@Composable
expect fun PdfPages(bytes: ByteArray, modifier: Modifier, onPage: (Int) -> Unit)

/** One page of a PDF as a PNG, e.g. to copy it; null where PDFs are not rendered. */
expect suspend fun pdfPagePng(bytes: ByteArray, page: Int): ByteArray?

/** Opens a PDF in the device's reader app; false when there is none (then the browser can). */
@Composable
expect fun rememberOpenPdf(): suspend (bytes: ByteArray, fileName: String) -> Boolean
