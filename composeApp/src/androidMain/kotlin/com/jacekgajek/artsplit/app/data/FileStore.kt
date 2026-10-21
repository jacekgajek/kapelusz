package com.jacekgajek.artsplit.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One file per key in an app-private directory; each write goes through its own temp file and an atomic rename. */
class FileStore(private val directory: File) : KeyValueStore {

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        file(key).takeIf { it.exists() }?.readText()
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File.createTempFile(key, ".tmp", directory)
        temp.writeText(value)
        check(temp.renameTo(file(key))) { "Could not save $key" }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { file(key).delete() }
    }

    private fun file(key: String) = File(directory, "$key.json")
}
