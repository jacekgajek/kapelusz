package com.jacekgajek.kapelusz.server.store

data class Blob(val content: String, val etag: String)

/** A presigned request: the URL plus the headers the client must send exactly as signed. */
data class PresignedRequest(val url: String, val headers: Map<String, String>)

/** Someone else changed the object since it was read (or it already exists). */
class PreconditionFailed : RuntimeException()

/** JSON objects with optimistic concurrency, i.e. S3 conditional writes. */
interface BlobStore {
    fun get(key: String): Blob?

    /**
     * Writes [content]. With [ifMatch] the write succeeds only if the object still has that ETag; with
     * [ifNoneMatch] only if the object does not exist yet. Returns the new ETag or throws [PreconditionFailed].
     */
    fun put(key: String, content: String, ifMatch: String? = null, ifNoneMatch: Boolean = false): String

    fun delete(key: String)

    /** Deletes [keys] in as few requests as possible (S3 takes up to 1000 per request). */
    fun deleteAll(keys: List<String>) = keys.forEach(::delete)

    /** Keys under [prefix], in lexicographic order. */
    fun list(prefix: String): List<String>

    fun exists(key: String): Boolean

    /**
     * A URL to upload a file of exactly [contentLength] bytes to [key]. It only creates the object: an existing file
     * is never replaced.
     */
    fun presignPut(key: String, contentType: String, contentLength: Long): PresignedRequest

    /** A URL to download [key], served as [contentType] and opened in place (not saved) by browsers. */
    fun presignGet(key: String, contentType: String, fileName: String): String
}
