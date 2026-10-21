package com.jacekgajek.artsplit.server

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.domain.SettlementCalculator
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.Engine
import kotlinx.datetime.LocalDate
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import kotlinx.serialization.Serializable
import org.crac.Core
import org.crac.Resource
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** API Gateway HTTP API, payload format 2.0 (only the fields we use). */
@Serializable
data class ApiGatewayRequest(
    val rawPath: String,
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
data class ApiGatewayResponse(val statusCode: Int, val headers: Map<String, String>, val body: String? = null)

/** Lambda entry point (`com.jacekgajek.artsplit.server.Handler::handleRequest`). */
class Handler : RequestStreamHandler {

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context?) {
        val event = ArtSplitJson.decodeFromString(ApiGatewayRequest.serializer(), input.readBytes().decodeToString())
        val body = event.body?.let { if (event.isBase64Encoded) Base64.getDecoder().decode(it).decodeToString() else it }
        val request = HttpRequest(
            method = event.requestContext.http.method,
            path = event.rawPath,
            headers = event.headers.mapKeys { it.key.lowercase() },
            body = body,
        )
        val response = try {
            api.handle(request)
        } catch (e: Exception) {
            System.err.println("Unhandled error for ${request.method} ${request.path}: ${e.stackTraceToString()}")
            HttpResponse(500, """{"error":"internal","message":"Internal error"}""", mapOf("content-type" to "application/json"))
        }
        val payload = ApiGatewayResponse(response.status, response.headers, response.body)
        output.write(ArtSplitJson.encodeToString(ApiGatewayResponse.serializer(), payload).toByteArray())
    }

    companion object : Resource {
        private val verifier = GoogleTokenVerifier(
            System.getenv("GOOGLE_CLIENT_IDS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
        private val store = S3BlobStore(System.getenv("DATA_BUCKET") ?: "jacekgajek-art-split-data")
        private val api = Api(store, verifier)

        init {
            Core.getGlobalContext().register(this)
        }

        /**
         * SnapStart priming: load classes and warm up the JIT before the snapshot is taken, so restored instances
         * answer fast. Runs once, when a version is published.
         */
        override fun beforeCheckpoint(context: org.crac.Context<out Resource>?) {
            runCatching { verifier.warmUp() }
            runCatching { store.get("priming/none.json") }
            val ops = listOf(
                CreateEvent("e", "Prime", LocalDate(2026, 1, 1), participants = listOf(Participant("m"))),
                AddHolding("e", "h", "m", Money(10000), IncomeSource.DOOR),
            )
            val sample = ops.fold(Band.create("prime", "Prime", "PLN", "m", "Prime", "u")) { band, op ->
                (Engine.apply(band, Actor.System, op, "prime", 0) as ApplyResult.Applied).band
            }
            repeat(50) {
                ArtSplitJson.decodeFromString(Band.serializer(), ArtSplitJson.encodeToString(Band.serializer(), sample))
                sample.events.forEach { SettlementCalculator.settle(sample, it) }
            }
            api.handle(HttpRequest("GET", "/me"))
        }

        override fun afterRestore(context: org.crac.Context<out Resource>?) = Unit
    }
}
