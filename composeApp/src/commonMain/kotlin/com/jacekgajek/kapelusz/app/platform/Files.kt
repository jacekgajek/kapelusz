package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import com.jacekgajek.kapelusz.model.AttachmentKind

/** A file ready to attach: photos are already compressed (longest edge 1600 px, JPEG ~80, no EXIF). */
class PreparedFile(val kind: AttachmentKind, val fileName: String, val sizeBytes: Long, val base64: String)

sealed interface PickResult {
    data class Picked(val file: PreparedFile) : PickResult
    data object TooLarge : PickResult
    data object Unsupported : PickResult
    data object Failed : PickResult
}

/**
 * Opens the platform's pickers; results (not cancellations) arrive in the callback given to [rememberAttachmentPicker],
 * one per chosen file: several can be picked at once (ITERATION_5 §4.7), the camera takes one.
 */
interface AttachmentPicker {
    /** Photos from the gallery or PDFs from files, one or several. */
    fun pickFile()

    /** Takes a photo with the camera; null where the file picker already offers the camera (the web). */
    val takePhoto: (() -> Unit)?
}

@Composable
expect fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker

/** PUTs the file to a presigned [url] with exactly the signed [headers]; returns the HTTP status. */
expect suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int

/**
 * Returns a function that opens the URL [resolve] produces (a presigned download) in the browser or system viewer.
 * On the web the window is opened right away, while the click still counts as a user action, so it is not blocked as a
 * popup; [resolve] returning null closes it again.
 */
@Composable
expect fun rememberUrlOpener(): (resolve: suspend () -> String?) -> Unit
