package com.jacekgajek.kapelusz.server.store

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** [BlobStore] in memory: for tests, and the local server's `--memory`. [clock] gives the time of each write. */
class InMemoryBlobStore(private val clock: () -> Long = System::currentTimeMillis) : BlobStore {
    val objects = ConcurrentHashMap<String, Blob>()

    /** When each object was last written. */
    private val written = ConcurrentHashMap<String, Long>()

    override fun get(key: String): Blob? = objects[key]

    @Synchronized
    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
        val current = objects[key]
        if (ifNoneMatch && current != null) throw PreconditionFailed()
        if (ifMatch != null && current?.etag != ifMatch) throw PreconditionFailed()
        val etag = "\"${UUID.randomUUID()}\""
        objects[key] = Blob(content, etag)
        written[key] = clock()
        return etag
    }

    override fun entries(prefix: String): List<BlobEntry> = objects.entries.filter { it.key.startsWith(prefix) }
        .map { (key, blob) -> BlobEntry(key, blob.content.toByteArray().size.toLong(), written[key] ?: 0) }.sortedBy { it.key }

    // Like S3, uploaded files are listed together with JSON objects.
    override fun list(prefix: String): List<String> = (objects.keys + files).filter { it.startsWith(prefix) }.sorted()

    /** Keys of uploaded files; tests add to it to simulate a client's upload. */
    val files: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun exists(key: String): Boolean = objects.containsKey(key) || key in files

    override fun presignPut(key: String, contentType: String, contentLength: Long) = PresignedRequest("memory://put/$key?length=$contentLength", mapOf("content-type" to contentType, "if-none-match" to "*"))

    override fun presignGet(key: String, contentType: String, fileName: String) = "memory://get/$key"

    override fun delete(key: String) {
        objects.remove(key)
        written.remove(key)
        files.remove(key)
    }
}
