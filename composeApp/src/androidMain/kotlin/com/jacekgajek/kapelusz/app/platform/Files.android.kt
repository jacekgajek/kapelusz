package com.jacekgajek.kapelusz.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.model.AttachmentKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_EDGE = 1600
private const val JPEG_QUALITY = 80

@Composable
internal fun rememberAndroidAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    // Survives the activity being recreated while the camera app is in front.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // One after another: each photo is decoded and re-encoded, several at once would need several times the memory.
        if (uris.isNotEmpty()) scope.launch { callback.value(uris.map { prepare(context, it) }) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val path = photoPath ?: return@rememberLauncherForActivityResult
        photoPath = null
        scope.launch {
            val file = File(path)
            if (taken) callback.value(listOf(prepare(context, Uri.fromFile(file), photo = true)))
            file.delete()
        }
    }
    return remember(files, camera) {
        object : AttachmentPicker {
            override fun pickFile() {
                try {
                    files.launch(arrayOf("image/*", "application/pdf"))
                } catch (e: ActivityNotFoundException) {
                    callback.value(listOf(PickResult.Failed))
                }
            }

            override val takePhoto: (() -> Unit) = {
                val file = File(context.cacheDir, "photos/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
                photoPath = file.path
                try {
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                } catch (e: ActivityNotFoundException) {
                    photoPath = null
                    callback.value(listOf(PickResult.Failed))
                }
            }
        }
    }
}

/**
 * Files shared to Kapelusz (I6 §1), prepared like picked ones, one after another. Only content URIs (no
 * `file://` paths into the app's own files); [sharedType] is the share's type, for providers that don't tell one.
 */
suspend fun prepareShared(context: Context, uris: List<Uri>, sharedType: String?): List<PickResult> = uris.map { uri -> if (uri.scheme == "content") prepare(context, uri, fallbackType = sharedType) else PickResult.Failed }

/** Largest file read at all: photos are shrunk after reading, but not from any size. */
private const val MAX_READ_BYTES = 40L * 1024 * 1024

private suspend fun prepare(context: Context, uri: Uri, photo: Boolean = false, fallbackType: String? = null): PickResult = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val type = if (photo) {
            "image/jpeg"
        } else {
            resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                ?: fallbackType?.takeIf { !it.contains('*') }.orEmpty()
        }
        var name: String? = null
        var size: Long? = null
        if (!photo) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    size = if (cursor.isNull(1)) null else cursor.getLong(1)
                }
            }
        }
        val limit = if (type == "application/pdf") AttachmentKind.PDF.maxBytes else MAX_READ_BYTES
        if ((size ?: 0) > limit) return@withContext PickResult.TooLarge
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext PickResult.Failed
        when {
            type == "application/pdf" -> {
                if (bytes.size > AttachmentKind.PDF.maxBytes) return@withContext PickResult.TooLarge
                PickResult.Picked(PreparedFile(AttachmentKind.PDF, name ?: "document.pdf", bytes.size.toLong(), encode(bytes)))
            }

            type.startsWith("image/") -> {
                val jpeg = compress(bytes) ?: return@withContext PickResult.Unsupported
                if (jpeg.size > AttachmentKind.IMAGE.maxBytes) return@withContext PickResult.TooLarge
                val fileName = (name?.substringBeforeLast('.') ?: "photo-${System.currentTimeMillis()}") + ".jpg"
                PickResult.Picked(PreparedFile(AttachmentKind.IMAGE, fileName, jpeg.size.toLong(), encode(jpeg)))
            }

            else -> PickResult.Unsupported
        }
    } catch (e: Exception) {
        PickResult.Failed
    } catch (e: OutOfMemoryError) {
        PickResult.TooLarge
    }
}

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Longest edge [MAX_EDGE], upright, JPEG [JPEG_QUALITY]; re-encoding writes no EXIF (so no GPS). Null if undecodable. */
private fun compress(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(1f, MAX_EDGE.toFloat() / max(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)

            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)

            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }
    val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (output !== decoded) decoded.recycle()
    return ByteArrayOutputStream().use { stream ->
        output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        output.recycle()
        stream.toByteArray()
    }
}

@Composable
internal fun rememberAndroidUrlOpener(): (resolve: suspend () -> String?) -> Unit {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) {
        { resolve ->
            scope.launch {
                runSuspendCatching { resolve()?.let(uriHandler::openUri) }
            }
        }
    }
}
