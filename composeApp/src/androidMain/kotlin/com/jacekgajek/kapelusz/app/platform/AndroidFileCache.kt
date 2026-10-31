package com.jacekgajek.kapelusz.app.platform

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attachment files in the app's cache directory (I5 §4.2): the oldest dropped above about 100 MB, and by the
 * system when it needs room.
 */
class AndroidFileCache(private val app: Context) : ByteCache {
    private fun dir(): File = File(app.cacheDir, "attachments").apply { mkdirs() }

    private fun name(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(dir(), name(key)).takeIf { it.isFile } ?: return@withContext null
        // Used now: the last to go when room is needed.
        file.setLastModified(System.currentTimeMillis())
        runCatching { file.readBytes() }.getOrNull()
    }

    override suspend fun write(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val dir = dir()
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

    private companion object {
        /** Above this the files used longest ago go (I5 §4.2). */
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}
