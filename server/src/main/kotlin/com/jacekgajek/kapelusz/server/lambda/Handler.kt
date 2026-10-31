package com.jacekgajek.kapelusz.server.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.server.Api
import com.jacekgajek.kapelusz.server.HttpRequest
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.ServerConfig
import com.jacekgajek.kapelusz.server.auth.GoogleTokenVerifier
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.links.NoWebIndex
import com.jacekgajek.kapelusz.server.links.S3WebIndex
import com.jacekgajek.kapelusz.server.malformedQuery
import com.jacekgajek.kapelusz.server.messaging.FcmSender
import com.jacekgajek.kapelusz.server.messaging.StoredServiceAccount
import com.jacekgajek.kapelusz.server.parseQuery
import com.jacekgajek.kapelusz.server.places.GooglePlaceSearch
import com.jacekgajek.kapelusz.server.store.CallTimer
import com.jacekgajek.kapelusz.server.store.DynamoGeocodeCache
import com.jacekgajek.kapelusz.server.store.DynamoHistoryStore
import com.jacekgajek.kapelusz.server.store.S3BlobStore
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.TimedBlobStore
import com.jacekgajek.kapelusz.server.unhandled
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.crac.Core
import org.crac.Resource
import software.amazon.awssdk.regions.Region

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
        val query = try {
            parseQuery(event.rawQueryString)
        } catch (ignored: IllegalArgumentException) {
            return malformedQuery()
        }
        val request = HttpRequest(
            method = event.requestContext.http.method,
            path = event.rawPath,
            headers = event.headers.mapKeys { it.key.lowercase() },
            body = body,
            query = query,
        )
        return try {
            api.handle(request)
        } catch (e: Exception) {
            unhandled(request.method, path, e)
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
        private val region = Region.of(System.getenv("AWS_REGION") ?: "eu-central-1")
        private val s3 = S3BlobStore.defaultClient(region)
        private val dynamo = DynamoHistoryStore.defaultClient(region)
        private val store = TimedBlobStore(S3BlobStore(System.getenv("DATA_BUCKET") ?: "jacekgajek-art-split-data", region, s3), s3Timer)
        private val history = DynamoHistoryStore(System.getenv("HISTORY_TABLE") ?: "art-split-history", dbTimer, dynamo)
        private val api = Api(
            store,
            history,
            verifier,
            placeSearch = System.getenv("GOOGLE_PLACES_API_KEY")?.takeIf { it.isNotBlank() }?.let(::GooglePlaceSearch),
            geocodeCache = DynamoGeocodeCache(System.getenv("GEOCODE_TABLE") ?: "art-split-geocode", dbTimer, dynamo),
            webIndex = System.getenv("WEB_BUCKET")?.takeIf { it.isNotBlank() }?.let { S3WebIndex(it, s3) } ?: NoWebIndex,
            // Sync messages (ITERATION_7 §2.5): the key is read on the first one, not into the SnapStart snapshot.
            messageSender = FcmSender(StoredServiceAccount(store)),
            config = ServerConfig(
                minAppVersion = Api.MIN_APP_VERSION,
                webUrl = System.getenv("WEB_URL") ?: ServerConfig.WEB_URL,
            ),
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
