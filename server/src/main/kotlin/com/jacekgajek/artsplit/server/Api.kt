package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.BandSummary
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.ErrorResponse
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.OpEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The user's band list, stored as `users/{googleSub}.json`. */
@Serializable
data class UserRecord(val userId: String, val bandIds: List<String> = emptyList())

/** One accepted push, stored under `bands/{id}/log/{yyyy-MM}/`. */
@Serializable
data class LogEntry(val userId: String, val memberId: String?, val at: Long, val ops: List<OpEnvelope>)

private class ApiError(val status: Int, val code: String, message: String) : RuntimeException(message)

/**
 * The HTTP API (REFINED_PLAN §5.3). Every operation goes through the shared [Engine], i.e. the same permission
 * checks and reducer the apps run, so clients cannot bypass the role rules.
 */
class Api(
    private val store: BlobStore,
    private val verifier: TokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (e: ApiError) {
        error(e.status, e.code, e.message.orEmpty())
    } catch (e: SerializationException) {
        error(400, "bad_request", e.message.orEmpty())
    }

    private fun route(request: HttpRequest): HttpResponse {
        val user = authenticate(request)
        val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            request.method == "GET" && segments == listOf("me") -> me(user)
            request.method == "POST" && segments == listOf("bands") -> createBand(user, parse(request, CreateBandRequest.serializer()))
            request.method == "GET" && segments.size == 3 && segments[0] == "bands" && segments[2] == "state" ->
                state(user, segments[1], request.header("if-none-match"))
            request.method == "POST" && segments.size == 3 && segments[0] == "bands" && segments[2] == "ops" ->
                push(user, segments[1], parse(request, PushRequest.serializer()))
            else -> throw ApiError(404, "not_found", "No route for ${request.method} ${request.path}")
        }
    }

    private fun authenticate(request: HttpRequest): VerifiedUser {
        val token = request.header("authorization")?.removePrefix("Bearer ")?.trim()
            ?: throw ApiError(401, "unauthorized", "Missing bearer token")
        return verifier.verify(token) ?: throw ApiError(401, "unauthorized", "Invalid or expired token")
    }

    // ---- endpoints ----

    private fun me(user: VerifiedUser): HttpResponse {
        val record = loadUser(user.userId)
        val bands = record.bandIds.mapNotNull { id ->
            val band = loadBand(id)?.first ?: return@mapNotNull null
            val member = band.memberOfUser(user.userId) ?: return@mapNotNull null
            BandSummary(band.id, band.name, member.name, member.role)
        }
        return ok(MeResponse.serializer(), MeResponse(user.userId, user.email, user.name, bands))
    }

    private fun createBand(user: VerifiedUser, request: CreateBandRequest): HttpResponse {
        badRequestIf(request.name.isBlank() || request.ownerName.isBlank()) { "Band and member names are required" }
        badRequestIf(!request.currency.matches(Regex("[A-Z]{3}"))) { "Currency must be an ISO code" }
        val band = Band.create(request.bandId, request.name.trim(), request.currency, request.ownerMemberId, request.ownerName.trim(), user.userId)
        val etag = try {
            store.put(bandKey(band.id), encode(Band.serializer(), band), ifNoneMatch = true)
        } catch (e: PreconditionFailed) {
            // A retried create of the caller's own band is fine; anything else is a conflict.
            val (existing, existingEtag) = loadBand(band.id) ?: throw ApiError(409, "conflict", "Band id is taken")
            if (existing.owner?.userId != user.userId) throw ApiError(409, "conflict", "Band id is taken")
            addBandToUser(user.userId, band.id)
            return ok(BandStateResponse.serializer(), BandStateResponse(existingEtag, existing))
        }
        addBandToUser(user.userId, band.id)
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band))
    }

    private fun state(user: VerifiedUser, bandId: String, ifNoneMatch: String?): HttpResponse {
        val (band, etag) = loadBandForMember(user, bandId)
        if (ifNoneMatch == etag) return HttpResponse(304, headers = mapOf("etag" to etag))
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band), mapOf("etag" to etag))
    }

    private fun push(user: VerifiedUser, bandId: String, request: PushRequest): HttpResponse {
        badRequestIf(request.ops.size > MAX_OPS_PER_PUSH) { "At most $MAX_OPS_PER_PUSH operations per push" }
        // Sequence numbers are tracked per user and device, so one user cannot block another user's device.
        val envelopes = request.ops.map { it.copy(clientId = "${user.userId}/${it.clientId}") }
        repeat(MAX_ATTEMPTS) {
            val (band, etag) = loadBandForMember(user, bandId)
            val result = Engine.applyBatch(band, Actor.User(user.userId), envelopes)
            val newEtag = try {
                if (result.band == band) etag else store.put(bandKey(bandId), encode(Band.serializer(), result.band), ifMatch = etag)
            } catch (e: PreconditionFailed) {
                return@repeat
            }
            if (result.accepted.isNotEmpty()) {
                // The change is already saved; a failed log write must not make the client retry it.
                runCatching { appendLog(user, band, bandId, envelopes.filter { it.opId in result.accepted }) }
                    .onFailure { System.err.println("Could not append history for band $bandId: $it") }
            }
            return ok(
                PushResponse.serializer(),
                PushResponse(newEtag, result.band, result.accepted, result.rejected, result.duplicates),
            )
        }
        throw ApiError(409, "busy", "Too many concurrent changes, try again")
    }

    // ---- storage ----

    private fun loadBand(bandId: String): Pair<Band, String>? =
        store.get(bandKey(bandId))?.let { decode(Band.serializer(), it.content) to it.etag }

    private fun loadBandForMember(user: VerifiedUser, bandId: String): Pair<Band, String> {
        val loaded = loadBand(bandId) ?: throw ApiError(404, "not_found", "No such band")
        val member = loaded.first.memberOfUser(user.userId)
        // Same answer for "no such band" and "not your band", so band ids cannot be probed.
        if (member == null || member.archived) throw ApiError(404, "not_found", "No such band")
        return loaded
    }

    private fun loadUser(userId: String): UserRecord =
        store.get(userKey(userId))?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)

    private fun addBandToUser(userId: String, bandId: String) {
        repeat(MAX_ATTEMPTS) {
            val blob = store.get(userKey(userId))
            val record = blob?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)
            if (bandId in record.bandIds) return
            val updated = encode(UserRecord.serializer(), record.copy(bandIds = record.bandIds + bandId))
            try {
                store.put(userKey(userId), updated, ifMatch = blob?.etag, ifNoneMatch = blob == null)
                return
            } catch (e: PreconditionFailed) {
                // retry with the fresh record
            }
        }
        throw ApiError(409, "busy", "Could not update the user record")
    }

    private fun appendLog(user: VerifiedUser, band: Band, bandId: String, ops: List<OpEnvelope>) {
        val now = clock()
        val month = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now))
        val entry = LogEntry(user.userId, band.memberOfUser(user.userId)?.id, now, ops)
        store.put("bands/$bandId/log/$month/$now-${UUID.randomUUID()}.json", encode(LogEntry.serializer(), entry), ifNoneMatch = true)
    }

    // ---- helpers ----

    private fun <T> parse(request: HttpRequest, serializer: KSerializer<T>): T =
        decode(serializer, request.body ?: throw ApiError(400, "bad_request", "Missing body"))

    private fun <T> ok(serializer: KSerializer<T>, value: T, headers: Map<String, String> = emptyMap()) =
        HttpResponse(200, encode(serializer, value), headers + JSON)

    private fun error(status: Int, code: String, message: String) =
        HttpResponse(status, encode(ErrorResponse.serializer(), ErrorResponse(code, message)), JSON)

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_OPS_PER_PUSH = 500
        private val JSON = mapOf("content-type" to "application/json")

        fun bandKey(bandId: String) = "bands/${safe(bandId)}/state.json"
        fun userKey(userId: String) = "users/${safe(userId)}.json"

        /** Ids end up in storage keys: allow only id-like characters. */
        private fun safe(id: String): String {
            badRequestIf(!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid id" }
            return id
        }

        private fun badRequestIf(condition: Boolean, message: () -> String) {
            if (condition) throw ApiError(400, "bad_request", message())
        }

        private fun <T> encode(serializer: KSerializer<T>, value: T) = ArtSplitJson.encodeToString(serializer, value)
        private fun <T> decode(serializer: KSerializer<T>, text: String) = ArtSplitJson.decodeFromString(serializer, text)
    }
}
