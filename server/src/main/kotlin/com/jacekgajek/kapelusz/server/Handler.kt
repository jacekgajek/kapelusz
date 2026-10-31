package com.jacekgajek.kapelusz.server

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.crac.Core
import org.crac.Resource

/** API Gateway HTTP API, payload format 2.0 (only the fields we use). */
@Serializable
data class ApiGatewayRequest(
    val rawPath: String,
    val rawQueryString: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val isBase64Encoded: Boolean = false,
    val requestContext: RequestContext,
) {
    @Serializable
    data class RequestContext(val http: Http)

    @Serializable
    data class Http(val method: String)
}

@Serializable
data class ApiGatewayResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String? = null,
    val isBase64Encoded: Boolean = false,
)

/** JSON bodies from this size on go out gzipped to a client that takes it; below it, gzip saves next to nothing. */
const val GZIP_MIN_BYTES = 1024

/**
 * [response] as API Gateway sends it on. A JSON body goes out gzipped when the client accepts gzip (the app's HTTP
 * stacks on Android and in browsers ask for it and unpack it themselves): a band's state, sent back on every push, is
 * repetitive JSON that shrinks to a fraction. HTML and calendar responses stay as they are: CloudFront compresses those.
 */
fun gatewayResponse(response: HttpResponse, acceptEncoding: String?): ApiGatewayResponse {
    val body = response.body
    val json = response.headers["content-type"]?.startsWith("application/json") == true
    if (body == null || !json || !acceptsGzip(acceptEncoding)) return ApiGatewayResponse(response.status, response.headers, body)
    val bytes = body.toByteArray()
    if (bytes.size < GZIP_MIN_BYTES) return ApiGatewayResponse(response.status, response.headers, body)
    val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    val headers = response.headers + mapOf("content-encoding" to "gzip", "vary" to "accept-encoding")
    return ApiGatewayResponse(response.status, headers, Base64.getEncoder().encodeToString(packed), isBase64Encoded = true)
}

private val REFUSED = Regex("q=0(\\.0*)?")

/** Whether an Accept-Encoding header takes gzip: listed by name (or else by `*`) and not refused by `q=0`. */
fun acceptsGzip(acceptEncoding: String?): Boolean {
    val codings = acceptEncoding.orEmpty().split(',').map { part -> part.split(';').map { it.replace(" ", "").lowercase() } }
    val gzip = codings.firstOrNull { it.first() == "gzip" } ?: codings.firstOrNull { it.first() == "*" } ?: return false
    return gzip.drop(1).none { it.matches(REFUSED) }
}

/** Lambda entry point (`com.jacekgajek.kapelusz.server.Handler::handleRequest`). */
class Handler : RequestStreamHandler {

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context?) {
        val start = System.nanoTime()
        s3Timer.reset()
        dbTimer.reset()
        var line = "?"
        var status = "-"
        try {
            val event = KapeluszJson.decodeFromString(ApiGatewayRequest.serializer(), input.readBytes().decodeToString())
            // Calendar feed paths and invite codes (also in join links) are secrets: never log them.
            val path = event.rawPath.replace(Regex("^/calendar/.*"), "/calendar/…").replace(Regex("/invites/[^/]+"), "/invites/…")
                .replace(Regex("^/join/.*"), "/join/…")
            line = "${event.requestContext.http.method} $path"
            val response = handle(event, path)
            status = response.status.toString()
            respond(output, gatewayResponse(response, event.headers.entries.firstOrNull { it.key.equals("accept-encoding", true) }?.value))
        } finally {
            // One line per request: where the time goes (V5 history latency).
            println("TIMING $line $status ${(System.nanoTime() - start) / 1_000_000}ms ${s3Timer.summary()} ${dbTimer.summary()}")
        }
    }

    private fun handle(event: ApiGatewayRequest, path: String): HttpResponse {
        val body = event.body?.let { if (event.isBase64Encoded) Base64.getDecoder().decode(it).decodeToString() else it }
        val request = try {
            HttpRequest(
                method = event.requestContext.http.method,
                path = event.rawPath,
                headers = event.headers.mapKeys { it.key.lowercase() },
                body = body,
                query = event.rawQueryString.split('&').filter { '=' in it }.associate {
                    val (key, value) = it.split('=', limit = 2)
                    URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
                },
            )
        } catch (e: IllegalArgumentException) {
            // A malformed %-escape in the query string.
            return error(400, ApiErrors.BAD_REQUEST, "Malformed query string")
        }
        return try {
            api.handle(request)
        } catch (e: Exception) {
            System.err.println("Unhandled error for ${request.method} $path: ${e.stackTraceToString()}")
            error(500, ApiErrors.INTERNAL, "Internal error")
        }
    }

    private fun respond(output: OutputStream, payload: ApiGatewayResponse) {
        output.write(KapeluszJson.encodeToString(ApiGatewayResponse.serializer(), payload).toByteArray())
    }

    companion object : Resource {
        init {
            // The JDK keeps only 5 idle connections per host by default (read once, on first use, so set before any
            // request): history reads 16 files at a time, and each read beyond 5 opened a new TLS connection to S3.
            System.setProperty("http.maxConnections", "32")
        }

        private val verifier = GoogleTokenVerifier(
            System.getenv("GOOGLE_CLIENT_IDS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
        private val s3Timer = CallTimer("s3")
        private val dbTimer = CallTimer("db")
        private val store = TimedBlobStore(S3BlobStore(System.getenv("DATA_BUCKET") ?: "jacekgajek-art-split-data"), s3Timer)
        private val history = DynamoHistoryStore(System.getenv("HISTORY_TABLE") ?: "art-split-history", dbTimer)
        private val api = Api(
            store,
            history,
            verifier,
            minAppVersion = Api.MIN_APP_VERSION,
            placeSearch = System.getenv("GOOGLE_PLACES_API_KEY")?.takeIf { it.isNotBlank() }?.let(::GooglePlaceSearch),
            geocodeCache = DynamoGeocodeCache(System.getenv("GEOCODE_TABLE") ?: "art-split-geocode", dbTimer),
            webIndex = System.getenv("WEB_BUCKET")?.takeIf { it.isNotBlank() }?.let { S3WebIndex(it) },
            // Sync messages (ITERATION_7 §2.5): the key is read on the first one, not into the SnapStart snapshot.
            messageSender = FcmSender(StoredServiceAccount(store)),
        )

        init {
            Core.getGlobalContext().register(this)
        }

        /**
         * SnapStart priming: load classes and warm up the JIT before the snapshot is taken, so restored instances
         * answer fast. Runs once, when a version is published.
         */
        override fun beforeCheckpoint(context: org.crac.Context<out Resource>?) {
            runCatching { verifier.warmUp() }
            runCatching { FcmSender.prime() }
            runCatching { store.get(StorageKeys.PRIMING) }
            runCatching { history.keys("priming") }
            val ops = listOf(
                CreateEvent("e", "Prime", LocalDate(2026, 1, 1), participants = listOf(Participant("m"))),
                AddHolding("e", "h", "m", Money(10000), IncomeSource.DOOR),
            )
            val sample = ops.fold(Band.create("prime", "Prime", "PLN", "m", "Prime", "u")) { band, op ->
                (Engine.apply(band, Actor.System, op, "prime", 0) as ApplyResult.Applied).band
            }
            repeat(50) {
                val encoded = KapeluszJson.encodeToString(Band.serializer(), sample)
                KapeluszJson.decodeFromString(Band.serializer(), encoded)
                gatewayResponse(HttpResponse(200, encoded.repeat(20), mapOf("content-type" to "application/json")), "gzip")
                sample.events.forEach { SettlementCalculator.settle(sample, it) }
            }
            // As a development build (always allowed), so priming goes past the version check into authentication.
            api.handle(HttpRequest("GET", "/me", query = mapOf(ApiErrors.APP_VERSION_PARAMETER to "0")), sweep = false)
        }

        override fun afterRestore(context: org.crac.Context<out Resource>?) = Unit
    }
}
