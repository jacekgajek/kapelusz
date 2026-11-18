package com.jacekgajek.kapelusz.server.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.server.HttpRequest
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.admin.AdminApi
import com.jacekgajek.kapelusz.server.admin.AdminConfig
import com.jacekgajek.kapelusz.server.admin.CloudWatchRequestLog
import com.jacekgajek.kapelusz.server.admin.RequestLog
import com.jacekgajek.kapelusz.server.auth.GoogleTokenVerifier
import com.jacekgajek.kapelusz.server.auth.TokenVerifier
import com.jacekgajek.kapelusz.server.malformedQuery
import com.jacekgajek.kapelusz.server.parseQuery
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.S3BlobStore
import com.jacekgajek.kapelusz.server.unhandled
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import software.amazon.awssdk.regions.Region

/**
 * The admin panel's Lambda entry point: its own function, with its own address and role. The app's API is [Handler].
 *
 * It is set up by `DATA_BUCKET`, `ADMIN_GOOGLE_CLIENT_ID` (the panel's own OAuth client, not the app's) and
 * `ADMIN_USER_IDS` (Google user ids, comma-separated); without the last two nobody gets in. `API_LOG_GROUP` names the
 * API Lambda's log group, where the panel finds the devices that could not sync.
 */
class AdminHandler : RequestStreamHandler {

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context?) {
        val start = System.nanoTime()
        var line = "?"
        var status = "-"
        try {
            val event = KapeluszJson.decodeFromString(ApiGatewayRequest.serializer(), input.readBytes().decodeToString())
            line = "${event.requestContext.http.method} ${event.rawPath}"
            val response = handle(event)
            status = response.status.toString()
            val acceptEncoding = event.headers.entries.firstOrNull { it.key.equals("accept-encoding", true) }?.value
            val payload = gatewayResponse(response, acceptEncoding)
            output.write(KapeluszJson.encodeToString(ApiGatewayResponse.serializer(), payload).toByteArray())
        } finally {
            println("ADMIN $line $status ${(System.nanoTime() - start) / 1_000_000}ms")
        }
    }

    private fun handle(event: ApiGatewayRequest): HttpResponse {
        val query = try {
            parseQuery(event.rawQueryString)
        } catch (ignored: IllegalArgumentException) {
            return malformedQuery()
        }
        val request = HttpRequest(
            method = event.requestContext.http.method,
            path = event.rawPath,
            headers = event.headers.mapKeys { it.key.lowercase() },
            body = event.body?.let { if (event.isBase64Encoded) Base64.getDecoder().decode(it).decodeToString() else it },
            query = query,
        )
        // An error nobody caught is logged and answered as a 500; anything worse than an exception goes on up.
        return runCatching { api.handle(request) }.getOrElse { e ->
            if (e is Exception) unhandled(request.method, request.path, e) else throw e
        }
    }

    companion object {
        private val region: Region = Region.of(System.getenv("AWS_REGION") ?: "eu-central-1")
        private val api = adminApi(
            store = S3BlobStore(System.getenv("DATA_BUCKET") ?: "jacekgajek-art-split-data", region),
            googleClientId = System.getenv("ADMIN_GOOGLE_CLIENT_ID"),
            userIds = System.getenv("ADMIN_USER_IDS"),
            // The API Lambda's log group; without it the panel shows no sync failures.
            requestLog = System.getenv("API_LOG_GROUP")?.takeIf { it.isNotBlank() }
                ?.let { CloudWatchRequestLog(it, CloudWatchRequestLog.defaultClient(region)) },
        )

        /**
         * The panel as the environment sets it up: [googleClientId] is the only audience a token may have, and
         * [userIds] (comma-separated) are the admins. Without a client id no token is taken at all, whatever
         * [userIds] says: a verifier with no audience must not mean "any audience".
         */
        fun adminApi(
            store: BlobStore,
            googleClientId: String?,
            userIds: String?,
            requestLog: RequestLog? = null,
            verifier: (Set<String>) -> TokenVerifier = ::GoogleTokenVerifier,
        ): AdminApi {
            val clientId = googleClientId?.trim().orEmpty()
            val admins = userIds.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return AdminApi(
                store = store,
                verifier = if (clientId.isEmpty()) TokenVerifier { null } else verifier(setOf(clientId)),
                config = AdminConfig(adminUserIds = if (clientId.isEmpty()) emptySet() else admins, googleClientId = clientId),
                requestLog = requestLog,
            )
        }
    }
}
