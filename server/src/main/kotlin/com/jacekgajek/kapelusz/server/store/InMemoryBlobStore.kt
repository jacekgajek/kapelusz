package com.jacekgajek.kapelusz.server.store

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** [BlobStore] in memory: for tests, and the local server's `--memory`. */
class InMemoryBlobStore : BlobStore {
    val objects = ConcurrentHashMap<String, Blob>()

    override fun get(key: String): Blob? = objects[key]

    @Synchronized
    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
        val current = objects[key]
        if (ifNoneMatch && current != null) throw PreconditionFailed()
        if (ifMatch != null && current?.etag != ifMatch) throw PreconditionFailed()
        val etag = "\"${UUID.randomUUID()}\""
        objects[key] = Blob(content, etag)
        return etag
    }

    // Like S3, uploaded files are listed together with JSON objects.
    override fun list(prefix: String): List<String> = (objects.keys + files).filter { it.startsWith(prefix) }.sorted()

    /** Keys of uploaded files; tests add to it to simulate a client's upload. */
    val files: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun exists(key: String): Boolean = objects.containsKey(key) || key in files

    override fun presignPut(key: String, contentType: String, contentLength: Long) = PresignedRequest("memory://put/$key?length=$contentLength", mapOf("content-type" to contentType, "if-none-match" to "*"))

    override fun presignGet(key: String, contentType: String, fileName: String) = "memory://get/$key"

    override fun delete(key: String) {
        objects.remove(key)
        files.remove(key)
    }
}
