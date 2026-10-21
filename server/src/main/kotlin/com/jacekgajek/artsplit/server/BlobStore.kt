package com.jacekgajek.artsplit.server

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Blob(val content: String, val etag: String)

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

    /** Keys under [prefix], in lexicographic order. */
    fun list(prefix: String): List<String>
}

class S3BlobStore(private val bucket: String, private val s3: S3Client = defaultClient()) : BlobStore {

    override fun get(key: String): Blob? = try {
        val response = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
        Blob(response.asUtf8String(), response.response().eTag())
    } catch (e: NoSuchKeyException) {
        null
    }

    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
        val request = PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/json")
            .apply { if (ifMatch != null) ifMatch(ifMatch) }
            .apply { if (ifNoneMatch) ifNoneMatch("*") }
            .build()
        return try {
            s3.putObject(request, RequestBody.fromString(content)).eTag()
        } catch (e: S3Exception) {
            // 412: condition not met; 409: a concurrent conditional write to the same key is in progress.
            if (e.statusCode() == 412 || e.statusCode() == 409) throw PreconditionFailed()
            throw e
        }
    }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    override fun list(prefix: String): List<String> =
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
            .contents().map { it.key() }.sorted()

    companion object {
        fun defaultClient(): S3Client = S3Client.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(Region.of(System.getenv("AWS_REGION") ?: "eu-central-1"))
            .build()
    }
}

/** For tests and local runs. */
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

    override fun delete(key: String) {
        objects.remove(key)
    }

    override fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }.sorted()
}
