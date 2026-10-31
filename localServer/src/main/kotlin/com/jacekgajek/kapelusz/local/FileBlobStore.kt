package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.server.Blob
import com.jacekgajek.kapelusz.server.BlobStore
import com.jacekgajek.kapelusz.server.PreconditionFailed
import com.jacekgajek.kapelusz.server.PresignedRequest
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * The data bucket as a directory: a key is a file under [root]. ETags are the content's MD5, as S3's are. One process
 * uses it, so conditional writes are made safe by synchronising. Presigned URLs point at this server's `/_files/`
 * ([FileRoutes]), on the host the request came to ([baseUrl]): `http://10.0.2.2:8787` from the emulator.
 */
class FileBlobStore(root: Path, private val baseUrl: () -> String) : BlobStore {
    private val root: Path = root.toAbsolutePath().normalize()

    fun path(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root) && path != root) { "Key outside the data directory: $key" }
        return path
    }

    override fun get(key: String): Blob? {
        val path = path(key)
        if (!path.isRegularFile()) return null
        val bytes = path.readBytes()
        return Blob(bytes.decodeToString(), etag(bytes))
    }

    @Synchronized
    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
        val path = path(key)
        val current = path.takeIf { it.isRegularFile() }?.readBytes()
        if (ifNoneMatch && current != null) throw PreconditionFailed()
        if (ifMatch != null && (current == null || etag(current) != ifMatch)) throw PreconditionFailed()
        val bytes = content.toByteArray()
        write(path, bytes)
        return etag(bytes)
    }

    /** An uploaded file: created only, never replaced (like the presigned PUT's `If-None-Match: *`). */
    @Synchronized
    fun create(key: String, bytes: ByteArray): Boolean {
        val path = path(key)
        if (path.exists()) return false
        write(path, bytes)
        return true
    }

    @Synchronized
    override fun delete(key: String) {
        Files.deleteIfExists(path(key))
    }

    @Synchronized
    override fun deleteAll(keys: List<String>) = keys.forEach(::delete)

    override fun list(prefix: String): List<String> {
        if (!root.exists()) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && !it.fileName.toString().endsWith(TEMP) }
                .map { it.relativeTo(root).invariantSeparatorsPathString }
                .filter { it.startsWith(prefix) }
                .sorted().toList()
        }
    }

    override fun exists(key: String): Boolean = path(key).isRegularFile()

    override fun presignPut(key: String, contentType: String, contentLength: Long) =
        PresignedRequest("${baseUrl()}${FileRoutes.PREFIX}$key?length=$contentLength", mapOf("content-type" to contentType))

    override fun presignGet(key: String, contentType: String, fileName: String): String =
        "${baseUrl()}${FileRoutes.PREFIX}$key?type=${encode(contentType)}&name=${encode(fileName)}"

    private companion object {
        fun etag(bytes: ByteArray) = "\"" + MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + "\""

        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
    }
}

/** Writes [bytes] to [path] whole or not at all: a temporary file moved into place. */
internal fun write(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    val temp = path.resolveSibling(path.fileName.toString() + TEMP)
    Files.write(temp, bytes)
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** Suffix of a file being written, never listed. */
internal const val TEMP = ".tmp"
