package com.jacekgajek.kapelusz.app.data

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One file per key in an app-private directory; each write goes through its own temp file and an atomic rename. */
class FileStore(private val directory: File) : KeyValueStore {

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        file(key).takeIf { it.exists() }?.readText()
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File.createTempFile(key, ".tmp", directory)
        // On the disk before it replaces the old file (ITERATION_3 R9): after a power loss, an older file coming back
        // would reuse sequence numbers the server has seen, and the next changes would be dropped as duplicates.
        try {
            FileOutputStream(temp).use { out ->
                out.write(value.toByteArray())
                out.fd.sync()
            }
            check(temp.renameTo(file(key))) { "Could not save $key" }
        } catch (e: Throwable) {
            // A failed write (e.g. a full device) leaves no temp file behind (V5-M1 D7).
            temp.delete()
            throw e
        }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { file(key).delete() }
    }

    private fun file(key: String) = File(directory, "$key.json")
}
