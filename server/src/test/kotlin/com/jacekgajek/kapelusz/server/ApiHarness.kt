package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.rules.Actor
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer

/**
 * One API in memory, as every test talks to it (REFACTORING.md 0.3): a test class extends it and gets the stores, a
 * clock it can move ([now]), and the calls every test makes. Tokens are user ids ([verifier]); "alice" owns "band1"
 * after [createBand]. A file that needs more (a fake sender, a web page) passes it on.
 */
open class ApiHarness(
    verifier: TokenVerifier = { token -> VerifiedUser(token, null, null) },
    /** 2026-09-21 14:13 UTC; a test moves it as it needs. */
    now: Long = 1_790_000_000_000,
    minAppVersion: Int = 0,
    placeSearch: PlaceSearch? = null,
    /** Kept for the test that gave it, to look at what the server did with it. */
    protected val webIndex: WebIndex = NoWebIndex,
    protected val messageSender: MessageSender = NoopMessageSender,
) {
    val store = InMemoryBlobStore()
    val history = InMemoryHistoryStore()

    /** The server's clock, in epoch milliseconds. */
    var now: Long = now

    val api = Api(
        store,
        history,
        verifier,
        clock = { this.now },
        placeSearch = placeSearch,
        webIndex = webIndex,
        messageSender = messageSender,
        config = ServerConfig(minAppVersion = minAppVersion),
    )

    /** A request as [user] (null: no token), to [on] (this API unless another is under test). */
    open fun call(
        method: String,
        path: String,
        user: String? = "alice",
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        on: Api = api,
    ): HttpResponse {
        val authorization = listOfNotNull(user?.let { "authorization" to "Bearer $it" })
        return on.handle(HttpRequest(method, path, headers + authorization, body, query))
    }

    /** The body of a 200 answer, decoded; anything else fails the test with the body. */
    fun <T> HttpResponse.parse(serializer: KSerializer<T>): T {
        assertEquals(200, status, body)
        return KapeluszJson.decodeFromString(serializer, body!!)
    }

    /** [user] creates [bandId] as its owner, member "m-<user>" named after them. */
    fun createBand(user: String = "alice", bandId: String = "band1", name: String = "Night Owls"): HttpResponse {
        val request = CreateBandRequest(bandId, name, "PLN", "m-$user", user.replaceFirstChar { it.uppercase() })
        return call("POST", "/bands", user, KapeluszJson.encodeToString(CreateBandRequest.serializer(), request))
    }

    /** Operation ids run on across pushes: "op1", "op2", … */
    var seq = 0L

    /** Pushes [ops] as one request from [user]'s [clientId], each in the next envelope ("op<n>", sequence and time n). */
    open fun push(user: String, vararg ops: Operation, bandId: String = "band1", clientId: String = "phone"): HttpResponse {
        val envelopes = ops.map { OpEnvelope("op${++seq}", clientId, seq, seq, it) }
        return call("POST", "/bands/$bandId/ops", user, KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes)))
    }

    /** [push], answered. */
    fun pushed(user: String, vararg ops: Operation, bandId: String = "band1"): PushResponse {
        val response = push(user, *ops, bandId = bandId)
        return response.parse(PushResponse.serializer())
    }

    /** An invite for [memberId] with [role], made by [by]. */
    fun invite(memberId: String = "m-bob", role: Role = Role.MEMBER, by: String = "alice", bandId: String = "band1"): HttpResponse {
        val request = CreateInviteRequest(memberId, role)
        return call("POST", "/bands/$bandId/invites", by, KapeluszJson.encodeToString(CreateInviteRequest.serializer(), request))
    }

    /** [invite]'s code. */
    fun inviteCode(memberId: String = "m-bob", role: Role = Role.MEMBER, by: String = "alice", bandId: String = "band1"): String {
        val made = invite(memberId, role, by, bandId)
        return made.parse(InviteInfo.serializer()).code
    }

    /** [user] joins as [memberId] through an invite. */
    fun join(user: String, memberId: String, role: Role = Role.MEMBER, bandId: String = "band1") {
        assertEquals(200, call("POST", "/invites/${inviteCode(memberId, role, bandId = bandId)}/accept", user).status)
    }

    /** Links [userId] to [memberId] in the stored band directly, the way an accepted invite does. */
    fun link(memberId: String, userId: String, role: Role, bandId: String = "band1") {
        val blob = store.get(StorageKeys.band(bandId))!!
        val band = KapeluszJson.decodeFromString(Band.serializer(), blob.content)
        val linked = (Engine.apply(band, Actor.System, LinkUser(memberId, userId, role), "link-$memberId", 0) as ApplyResult.Applied).band
        store.put(StorageKeys.band(bandId), KapeluszJson.encodeToString(Band.serializer(), linked), ifMatch = blob.etag)
    }
}
