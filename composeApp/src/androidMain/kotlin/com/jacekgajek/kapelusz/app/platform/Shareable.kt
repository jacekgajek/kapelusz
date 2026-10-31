package com.jacekgajek.kapelusz.app.platform

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/** [bytes] in a file in the cache's [folder] under [fileName] (made safe, with [extension]), for another app to read. */
internal fun shareable(context: Context, folder: String, fileName: String, extension: String, bytes: ByteArray): android.net.Uri {
    val dir = File(context.cacheDir, folder).apply { mkdirs() }
    // Only the latest: earlier ones were for an app that has read them by now.
    dir.listFiles()?.forEach { it.delete() }
    val base = fileName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifBlank { "file" }
    val file = File(dir, "$base.$extension")
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
