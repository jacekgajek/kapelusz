package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.server.Api
import com.jacekgajek.kapelusz.server.HttpRequest
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.ServerConfig
import com.jacekgajek.kapelusz.server.links.FileWebIndex
import com.jacekgajek.kapelusz.server.links.NoWebIndex
import com.jacekgajek.kapelusz.server.malformedQuery
import com.jacekgajek.kapelusz.server.parseQuery
import com.jacekgajek.kapelusz.server.store.InMemoryBlobStore
import com.jacekgajek.kapelusz.server.store.InMemoryGeocodeCache
import com.jacekgajek.kapelusz.server.store.InMemoryHistoryStore
import com.jacekgajek.kapelusz.server.unhandled
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.readBytes

/**
 * The Kapelusz API on this machine (LOCAL_DEVELOPMENT.md): the same [Api] as the Lambda, with the bucket and the history
 * table as files under [dataDir] (or in memory, gone with the process, with [inMemory]), development sign-in instead of
 * Google's, and made-up places. No sync messages (Firebase needs Google): the apps sync every 30 seconds instead.
 * Event link cards need the web app's `index.html` ([webApp]); without it they are 404. Listens on the loopback only;
 * the emulator reaches it as `10.0.2.2`.
 */
class LocalServer(
    dataDir: Path,
    port: Int,
    host: InetAddress = InetAddress.getLoopbackAddress(),
    inMemory: Boolean = false,
    webApp: Path? = null,
    webUrl: String = ServerConfig.WEB_URL,
) {
    /** The address the current request came to, for presigned URLs that lead back to this server. */
    private val requestBase = ThreadLocal.withInitial { "http://localhost:$port" }
    private val fileStore = if (inMemory) null else FileBlobStore(dataDir.resolve("bucket")) { requestBase.get() }
    private val api = Api(
        fileStore ?: InMemoryBlobStore(),
        if (inMemory) InMemoryHistoryStore() else FileHistoryStore(dataDir.resolve("history")),
        DevTokenVerifier(),
        placeSearch = OfflinePlaceSearch(),
        geocodeCache = InMemoryGeocodeCache(),
        webIndex = webApp?.let(::FileWebIndex) ?: NoWebIndex,
        config = ServerConfig(webUrl = webUrl),
    )

    /** The uploads and downloads of the file store; in memory, presigned links lead nowhere (no attachments). */
    private val files = fileStore?.let(::FileRoutes)
    private val server = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        createContext("/") { exchange -> exchange.use(::serve) }
        executor = Executors.newFixedThreadPool(8)
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    fun stop() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    private fun serve(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        val response = try {
            exchange.requestHeaders.getFirst("host")?.let { requestBase.set("http://$it") }
            // A malformed %-escape in the query string, as the Lambda's Handler answers it.
            val query = try {
                parseQuery(exchange.requestURI.rawQuery)
            } catch (ignored: IllegalArgumentException) {
                null
            }
            when {
                query == null -> Reply.of(malformedQuery())

                // CORS preflight from the web app: answered here, as API Gateway does in front of the Lambda.
                method == "OPTIONS" -> Reply(204)

                files != null && path.startsWith(FileRoutes.PREFIX) ->
                    files.handle(method, path.removePrefix(FileRoutes.PREFIX), query, exchange.requestBody.readBytes())

                else -> Reply.of(api.handle(request(exchange, query)))
            }
        } catch (e: Exception) {
            Reply.of(unhandled(method, path, e))
        }
        println("$method $path ${response.status}")
        send(exchange, response, method)
    }

    private fun request(exchange: HttpExchange, query: Map<String, String>) = HttpRequest(
        method = exchange.requestMethod,
        path = exchange.requestURI.rawPath,
        headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString(",") },
        body = exchange.requestBody.readBytes().takeIf { it.isNotEmpty() }?.decodeToString(),
        query = query,
    )

    private fun send(exchange: HttpExchange, response: Reply, method: String) {
        val headers = exchange.responseHeaders
        response.headers.forEach { (name, value) -> headers.set(name, value) }
        // What the API's CORS configuration allows (my-aws, kapelusz/api.tf), for the web app on another port.
        headers.set("access-control-allow-origin", "*")
        headers.set("access-control-allow-methods", "GET, HEAD, POST, PUT, DELETE, OPTIONS")
        headers.set("access-control-allow-headers", "authorization, content-type, if-none-match")
        headers.set("access-control-expose-headers", "etag")
        val bytes = response.body
        if (bytes == null || method == "HEAD" || response.status == 204 || response.status == 304) {
            exchange.sendResponseHeaders(response.status, -1)
        } else {
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }
}

/** A response as sent: the API's text, or a file's bytes. */
class Reply(val status: Int, val body: ByteArray? = null, val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun of(response: HttpResponse) = Reply(response.status, response.body?.toByteArray(), response.headers)

        fun text(status: Int, message: String) = Reply(status, message.toByteArray(), mapOf("content-type" to "text/plain; charset=utf-8"))
    }
}

/** The uploads and downloads that S3 serves for the real API, through the URLs [FileBlobStore] presigns. */
class FileRoutes(private val store: FileBlobStore) {

    fun handle(method: String, key: String, query: Map<String, String>, body: ByteArray): Reply = when (method) {
        "PUT" -> {
            val length = query["length"]?.toLongOrNull()
            when {
                // Signed for exactly this size, as S3's presigned PUT is.
                length != body.size.toLong() -> Reply.text(403, "Size differs from the signed one")

                runCatching { store.path(key) }.isFailure -> Reply.text(403, "Not a file of the data directory")

                store.create(key, body) -> Reply(200)

                else -> Reply.text(412, "Already exists")
            }
        }

        "GET", "HEAD" -> {
            // A key outside the data directory is no file of it.
            val path = runCatching { store.path(key) }.getOrNull()
            if (path == null || !Files.isRegularFile(path)) {
                Reply.text(404, "No such file")
            } else {
                val name = query["name"]?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                Reply(
                    200,
                    path.readBytes(),
                    mapOfNotNull("content-type" to (query["type"] ?: "application/octet-stream"), name?.let { "content-disposition" to "inline; filename=\"$it\"" }),
                )
            }
        }

        else -> Reply.text(405, "Method not allowed")
    }

    companion object {
        const val PREFIX = "/_files/"
    }
}

private fun mapOfNotNull(vararg pairs: Pair<String, String>?) = pairs.filterNotNull().toMap()

/** `--memory`: nothing written, the data goes with the process. The environment (LOCAL_DEVELOPMENT.md) sets the rest. */
fun main(args: Array<String>) {
    val inMemory = "--memory" in args
    val dataDir = Path.of(System.getenv("KAPELUSZ_LOCAL_DATA") ?: ".local-data").toAbsolutePath().normalize()
    val port = System.getenv("KAPELUSZ_LOCAL_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val webApp = System.getenv("KAPELUSZ_LOCAL_WEB")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
    val webUrl = System.getenv("WEB_URL") ?: ServerConfig.WEB_URL
    if (!inMemory) Files.createDirectories(dataDir)
    LocalServer(dataDir, port, inMemory = inMemory, webApp = webApp, webUrl = webUrl).start()
    val data = if (inMemory) "in memory" else "in $dataDir"
    println("Kapelusz local API on http://localhost:$port (emulator: http://10.0.2.2:$port), data $data")
}

const val DEFAULT_PORT = 8787
