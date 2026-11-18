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
import com.jacekgajek.kapelusz.server.store.CallTimer
import com.jacekgajek.kapelusz.server.store.DynamoHistoryStore
import com.jacekgajek.kapelusz.server.store.HistoryStore
import com.jacekgajek.kapelusz.server.store.S3BlobStore
import com.jacekgajek.kapelusz.server.unhandled
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import software.amazon.awssdk.regions.Region

/**
 * Where the admin panel's Lambda starts. It is its own function, with its own address and role. The app's API
 * starts in [Handler].
 *
 * It reads these variables:
 * - `DATA_BUCKET`: the bucket with the bands' data.
 * - `ADMIN_GOOGLE_CLIENT_ID`: the panel's own OAuth client. It is not the app's client.
 * - `ADMIN_USER_IDS`: the Google user ids of the admins, separated by commas.
 * - `API_LOG_GROUP`: the API Lambda's log group. The panel finds the devices that could not sync there.
 * - `HISTORY_TABLE`: the table of the bands' history. A restore adds an entry to it.
 *
 * Without the client id or without user ids, nobody gets in.
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
        // An exception that nobody caught is logged and answered with a 500. Anything worse than an exception is thrown on.
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
            // The bands' history table. A restore adds an entry there, for the members to see.
            historyStore = System.getenv("HISTORY_TABLE")?.takeIf { it.isNotBlank() }
                ?.let { DynamoHistoryStore(it, CallTimer("db"), DynamoHistoryStore.defaultClient(region)) },
            // The API Lambda's log group. Without it the panel shows no sync failures.
            requestLog = System.getenv("API_LOG_GROUP")?.takeIf { it.isNotBlank() }
                ?.let { CloudWatchRequestLog(it, CloudWatchRequestLog.defaultClient(region)) },
        )

        /**
         * Builds the panel from the values of the environment.
         *
         * A token must be made for [googleClientId] and for no other client. [userIds] are the admins, separated by
         * commas. Without a client id, no token is accepted at all, whatever [userIds] says. A verifier with no
         * client must never mean "any client".
         */
        fun adminApi(
            store: BlobStore,
            googleClientId: String?,
            userIds: String?,
            requestLog: RequestLog? = null,
            historyStore: HistoryStore? = null,
            verifier: (Set<String>) -> TokenVerifier = ::GoogleTokenVerifier,
        ): AdminApi {
            val clientId = googleClientId?.trim().orEmpty()
            val admins = userIds.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return AdminApi(
                store = store,
                verifier = if (clientId.isEmpty()) TokenVerifier { null } else verifier(setOf(clientId)),
                config = AdminConfig(adminUserIds = if (clientId.isEmpty()) emptySet() else admins, googleClientId = clientId),
                requestLog = requestLog,
                historyStore = historyStore,
            )
        }
    }
}
