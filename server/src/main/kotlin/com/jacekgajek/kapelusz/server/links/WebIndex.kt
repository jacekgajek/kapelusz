package com.jacekgajek.kapelusz.server.links

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/** The web app's `index.html`, which link pages are made of. */
fun interface WebIndex {
    /** The page, or null when it can't be had (then the plain page is served by CloudFront). */
    fun html(): String?
}

/** No page at hand: event links are 404 here, and CloudFront answers with the plain page. */
object NoWebIndex : WebIndex {
    override fun html(): String? = null
}

/** `index.html` as a file (the web app's build, for the local server), read on every use so a rebuild shows at once. */
class FileWebIndex(private val path: Path) : WebIndex {
    override fun html(): String? = runCatching { if (path.isRegularFile()) path.readText() else null }
        .onFailure { System.err.println("Could not read the web app's index.html at $path: $it") }
        .getOrNull()
}

/** `index.html` from the web app's bucket, kept for a few seconds: a deploy changes it. */
class S3WebIndex(private val bucket: String, private val s3: S3Client, private val clock: () -> Long = System::currentTimeMillis) : WebIndex {
    @Volatile private var cached: Pair<Long, String>? = null

    override fun html(): String? {
        cached?.let { (at, html) -> if (clock() - at < KEEP_MS) return html }
        val html = try {
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key("index.html").build()).asUtf8String()
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: Exception) {
            System.err.println("Could not read the web app's index.html: $e")
            // The last one rather than none.
            return cached?.second
        }
        if (html != null) cached = clock() to html
        return html
    }

    private companion object {
        const val KEEP_MS = 15_000L
    }
}
