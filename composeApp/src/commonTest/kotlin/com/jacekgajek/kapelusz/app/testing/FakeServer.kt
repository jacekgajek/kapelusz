package com.jacekgajek.kapelusz.app.testing

import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.allAttachments
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.rules.Actor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer

/**
 * A tiny in-memory server running the real shared [Engine], behind Ktor's mock HTTP engine. It answers on [dispatcher]:
 * unconfined by default, so an answer comes on the caller's thread and a test on virtual time (`runTest`,
 * `advanceTimeBy`) sees every round through; a test dispatcher of the scheduler would leave Ktor's reading of the
 * answer waiting on a thread the scheduler never runs.
 */
internal class FakeServer(private val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
    val bands = mutableMapOf<String, Band>()
    var version = 0
    var unauthorizedOnce = false

    /** The server no longer serves this app version. */
    var updateRequired = false
    var requests = 0
    val appVersionsSeen = mutableListOf<String?>()

    /** Bands their owner deleted: former members get band_deleted. */
    val deletedBands = mutableSetOf<String>()

    /** False simulates a server from before attachments: the route does not exist. */
    var attachmentsRoute = true

    /** Simulates no network; a Throwable that is not an Exception, like the browser's JsException. */
    var offline = false
    val tokensSeen = mutableListOf<String>()

    /** Bands whose pushes are refused as a whole (`400`), e.g. too many devices. */
    val refusePushes = mutableSetOf<String>()
    val pushedBands = mutableListOf<String>()

    /** Leaves already done, and whether the next one's answer is lost on the way (it is done all the same). */
    val leaves = mutableListOf<String>()
    var loseLeaveAnswer = false

    /** Answers /me with something that is not its JSON (a proxy's page, a broken deploy). */
    var unreadableMe = false

    val client = HttpClient(MockEngine) {
        engine {
            this.dispatcher = this@FakeServer.dispatcher
            addHandler { request ->
                if (offline) throw object : Throwable("Failed to fetch") {}
                requests++
                appVersionsSeen += request.url.parameters["appVersion"]
                if (updateRequired) {
                    return@addHandler respond("""{"error":"update_required","message":"Too old"}""", HttpStatusCode.UpgradeRequired, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val token = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
                tokensSeen += token
                if (unauthorizedOnce) {
                    unauthorizedOnce = false
                    return@addHandler respond("""{"error":"unauthorized","message":"expired"}""", HttpStatusCode.Unauthorized)
                }
                val user = token.substringBefore(':')
                val body = (request.body as? TextContent)?.text
                val path = request.url.encodedPath
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                val bandInPath = path.split('/').getOrNull(2)
                if (path.startsWith("/bands/") && bandInPath in deletedBands) {
                    return@addHandler respond("""{"error":"band_deleted","message":"Deleted"}""", HttpStatusCode.NotFound, json)
                }
                if (path.startsWith("/bands/") && bandInPath !in bands) {
                    return@addHandler respond("""{"error":"band_not_found","message":"No such band"}""", HttpStatusCode.NotFound, json)
                }
                fun <T> ok(serializer: KSerializer<T>, value: T) = respond(KapeluszJson.encodeToString(serializer, value), HttpStatusCode.OK, json)
                when {
                    path == "/me" && unreadableMe -> respond("""{"userId":""", HttpStatusCode.OK, json)

                    path == "/me" -> ok(
                        MeResponse.serializer(),
                        MeResponse(
                            user,
                            bands = bands.values.mapNotNull { band ->
                                band.memberOfUser(user)?.let { BandSummary(band.id, band.name, it.name, it.role) }
                            },
                        ),
                    )

                    path == "/bands" -> {
                        val create = KapeluszJson.decodeFromString(CreateBandRequest.serializer(), body!!)
                        val band = Band.create(create.bandId, create.name, create.currency, create.ownerMemberId, create.ownerName, user)
                        bands[band.id] = band
                        ok(BandStateResponse.serializer(), BandStateResponse("v${++version}", band))
                    }

                    path.endsWith("/state") -> {
                        val band = bands.getValue(path.split('/')[2])
                        if (request.headers[HttpHeaders.IfNoneMatch] == "v$version") {
                            respond("", HttpStatusCode.NotModified)
                        } else {
                            ok(BandStateResponse.serializer(), BandStateResponse("v$version", band))
                        }
                    }

                    path.endsWith("/ops") && bandInPath in refusePushes -> {
                        pushedBands += bandInPath!!
                        respond("""{"error":"too_many_devices","message":"Too many devices"}""", HttpStatusCode.BadRequest, json)
                    }

                    path.endsWith("/ops") -> {
                        val bandId = path.split('/')[2]
                        pushedBands += bandId
                        val push = KapeluszJson.decodeFromString(PushRequest.serializer(), body!!)
                        val result = Engine.applyBatch(bands.getValue(bandId), Actor.User(user), push.ops)
                        bands[bandId] = result.band
                        ok(PushResponse.serializer(), PushResponse("v${++version}", result.band, result.accepted, result.rejected, result.duplicates))
                    }

                    path.endsWith("/leave") -> {
                        val bandId = path.split('/')[2]
                        val band = bands.getValue(bandId)
                        val me = band.memberOfUser(user)
                        if (me == null) {
                            respond("""{"error":"band_not_found","message":"No such band"}""", HttpStatusCode.NotFound, json)
                        } else {
                            leaves += user
                            serverSide(bandId, UnlinkUser(me.id))
                            if (loseLeaveAnswer) {
                                loseLeaveAnswer = false
                                throw object : Throwable("Connection reset") {}
                            }
                            respond("", HttpStatusCode.NoContent)
                        }
                    }

                    request.method == HttpMethod.Get && path.matches(Regex("/bands/[^/]+/attachments/[^/]+")) ->
                        respond("""{"url":"https://s3.test/get/${path.substringAfterLast('/')}"}""", HttpStatusCode.OK, json)

                    path.endsWith("/attachments") && !attachmentsRoute -> respond("""{"error":"not_found","message":"No route"}""", HttpStatusCode.NotFound, json)

                    path.endsWith("/attachments") -> {
                        val upload = KapeluszJson.decodeFromString(AttachmentUploadRequest.serializer(), body!!)
                        val listed = bands.getValue(path.split('/')[2]).allAttachments().any { it.id == upload.attachmentId }
                        if (!listed) {
                            respond("""{"error":"attachment_not_found","message":"No such attachment"}""", HttpStatusCode.NotFound, json)
                        } else {
                            ok(AttachmentUploadResponse.serializer(), AttachmentUploadResponse("https://s3.test/${upload.attachmentId}", mapOf("content-type" to "image/jpeg")))
                        }
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        }
    }

    /** Changes made by someone else directly on the server. */
    fun serverSide(bandId: String, vararg ops: Operation) {
        bands[bandId] = ops.fold(bands.getValue(bandId)) { band, op -> (Engine.apply(band, Actor.System, op, "srv", 0) as ApplyResult.Applied).band }
        version++
    }
}
