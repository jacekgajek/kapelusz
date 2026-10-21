package com.jacekgajek.artsplit.server

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

class S3BlobStore(
    private val bucket: String,
    private val s3: S3Client = defaultClient(),
    private val presigner: S3Presigner = S3Presigner.builder().region(region()).build(),
) : BlobStore {

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

    override fun deleteAll(keys: List<String>) {
        keys.chunked(1000).forEach { chunk ->
            s3.deleteObjects(
                DeleteObjectsRequest.builder().bucket(bucket)
                    .delete(Delete.builder().objects(chunk.map { ObjectIdentifier.builder().key(it).build() }).quiet(true).build())
                    .build(),
            )
        }
    }

    override fun list(prefix: String): List<String> =
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
            .contents().map { it.key() }.sorted()

    override fun exists(key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    }

    override fun presignPut(key: String, contentType: String, contentLength: Long): PresignedRequest {
        val put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)
            .ifNoneMatch("*").build()
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(PRESIGN_DURATION).putObjectRequest(put).build(),
        )
        // Browsers set Host and Content-Length themselves (and refuse to set them); the rest the client must send.
        val headers = presigned.signedHeaders()
            .filterKeys { it.lowercase() !in setOf("host", "content-length") }
            .mapValues { it.value.joinToString(",") }
        return PresignedRequest(presigned.url().toString(), headers)
    }

    override fun presignGet(key: String, contentType: String, fileName: String): String {
        val get = GetObjectRequest.builder().bucket(bucket).key(key).responseContentType(contentType)
            .responseContentDisposition("inline; filename=\"${fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_")}\"").build()
        return presigner.presignGetObject(
            GetObjectPresignRequest.builder().signatureDuration(PRESIGN_DURATION).getObjectRequest(get).build(),
        ).url().toString()
    }

    companion object {
        private val PRESIGN_DURATION: Duration = Duration.ofMinutes(15)

        private fun region(): Region = Region.of(System.getenv("AWS_REGION") ?: "eu-central-1")

        fun defaultClient(): S3Client = S3Client.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(region())
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

    // Like S3, uploaded files are listed together with JSON objects.
    override fun list(prefix: String): List<String> = (objects.keys + files).filter { it.startsWith(prefix) }.sorted()

    /** Keys of uploaded files; tests add to it to simulate a client's upload. */
    val files: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun exists(key: String): Boolean = objects.containsKey(key) || key in files

    override fun presignPut(key: String, contentType: String, contentLength: Long) =
        PresignedRequest("memory://put/$key?length=$contentLength", mapOf("content-type" to contentType, "if-none-match" to "*"))

    override fun presignGet(key: String, contentType: String, fileName: String) = "memory://get/$key"

    override fun delete(key: String) {
        objects.remove(key)
        files.remove(key)
    }
}
