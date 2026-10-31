package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.DeviceRegistration
import com.jacekgajek.kapelusz.api.DeviceRegistrationResponse
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.server.devices.DeviceRegistry
import com.jacekgajek.kapelusz.server.messaging.MessageSender
import com.jacekgajek.kapelusz.server.messaging.SendResult
import com.jacekgajek.kapelusz.server.store.StoredDevices
import com.jacekgajek.kapelusz.server.store.devices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records what would be sent; tokens in [invalid] are reported as no longer valid. */
private class FakeSender : MessageSender {
    val sent = mutableListOf<Pair<String, String>>()
    val invalid = mutableSetOf<String>()
    var ready = true
    override fun ready() = ready
    override fun send(tokens: List<String>, data: Map<String, String>) = tokens.associateWith { token ->
        sent += token to data.getValue("band")
        if (token in invalid) SendResult.INVALID_TOKEN else SendResult.SENT
    }
}

/** Sync messages: who gets them, and the device registry behind them. */
class SyncMessagesTest : ApiHarness(messageSender = FakeSender()) {
    private val sender get() = messageSender as FakeSender

    /** The request comes from the device [installation]. */
    private fun from(installation: String) = mapOf("installation" to installation)

    /** Whether messages will come, as the server answers the registration. */
    private fun register(user: String, installation: String, token: String = "token-$installation-0123456789"): Boolean {
        val response = call("PUT", "/me/devices/$installation", user, KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration(token)))
        assertEquals(200, response.status)
        return KapeluszJson.decodeFromString(DeviceRegistrationResponse.serializer(), response.body!!).messaging
    }

    /** A push from the device [installation], with its own sequence [seq]. */
    private fun pushFrom(user: String, vararg ops: com.jacekgajek.kapelusz.ops.Operation, installation: String, seq: Long) {
        val envelopes = ops.mapIndexed { i, op -> OpEnvelope("$user-$seq-$i", "phone", seq + i, 1, op) }
        val body = KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes))
        assertEquals(200, call("POST", "/bands/band1/ops", user, body, query = from(installation)).status)
    }

    /** Alice owns band1; Bob and Carol joined; Dan is a member without the app. Each has devices registered. */
    private fun setup() {
        createBand()
        push("alice", AddMember("m-bob", "Bob"), AddMember("m-carol", "Carol"), AddMember("m-dan", "Dan"))
        join("bob", "m-bob", Role.LEAD)
        join("carol", "m-carol", Role.LEAD)
        register("alice", "alice-phone")
        register("bob", "bob-phone")
        register("bob", "bob-tablet")
        register("carol", "carol-phone")
        sender.sent.clear()
    }

    private fun recipients() = sender.sent.map { it.first.removePrefix("token-").removeSuffix("-0123456789") }.toSet()

    @Test
    fun aChangeReachesTheOtherDevicesOfTheBandButNotTheOneThatMadeIt() {
        setup()
        pushFrom("bob", RenameBand("Owls"), installation = "bob-phone", seq = 10)
        assertEquals(setOf("alice-phone", "bob-tablet", "carol-phone"), recipients())
        assertTrue(sender.sent.all { it.second == "band1" }, "only the band's id")
    }

    @Test
    fun aRefusedOrEmptyPushSendsNothing() {
        setup()
        // A member id that is taken: the push is answered, the change refused.
        pushFrom("alice", AddMember("m-bob", "Bob again"), installation = "alice-phone", seq = 10)
        assertEquals(emptyList(), sender.sent, "refused: nothing to tell")
    }

    @Test
    fun aMemberMovedToFormerMembersHearsOfItOnceAndThenNoMore() {
        setup()
        pushFrom("alice", SetMemberArchived("m-carol", true), installation = "alice-phone", seq = 10)
        assertTrue("carol-phone" in recipients(), "learns at once that the band is gone for her")
        sender.sent.clear()
        pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 20)
        assertEquals(setOf("bob-phone", "bob-tablet"), recipients())
    }

    @Test
    fun joiningAndLeavingTellTheBand() {
        setup()
        call("POST", "/bands/band1/leave", "bob", query = from("bob-phone"))
        assertEquals(setOf("alice-phone", "bob-tablet", "carol-phone"), recipients(), "Bob's tablet lets go of the band too")
    }

    @Test
    fun deletingTellsEveryone() {
        setup()
        call("DELETE", "/bands/band1", "alice", query = from("alice-phone"))
        assertEquals(setOf("bob-phone", "bob-tablet", "carol-phone"), recipients())
    }

    @Test
    fun anInvalidTokenIsForgotten() {
        setup()
        sender.invalid += "token-bob-tablet-0123456789"
        pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
        val bobs = KapeluszJson.decodeFromString(StoredDevices.serializer(), store.get("users/bob-devices.json")!!.content).devices
        assertEquals(listOf("bob-phone"), bobs.map { it.installationId })
    }

    @Test
    fun signingOutOneDeviceOrEverywhere() {
        setup()
        assertEquals(204, call("DELETE", "/me/devices/bob-tablet", "bob").status)
        pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
        assertEquals(setOf("bob-phone", "carol-phone"), recipients())
        sender.sent.clear()
        assertEquals(204, call("POST", "/sessions/revoke", "carol").status)
        assertNull(store.get("users/carol-devices.json"))
    }

    @Test
    fun theRegistryKeepsTenDevicesAndForgetsUnseenOnes() {
        setup()
        repeat(12) { i ->
            now += 1000
            register("bob", "bob-device-$i")
        }
        val bobs = KapeluszJson.decodeFromString(StoredDevices.serializer(), store.get("users/bob-devices.json")!!.content).devices
        assertEquals(DeviceRegistry.MAX_DEVICES, bobs.size)
        assertTrue("bob-device-11" in bobs.map { it.installationId } && "bob-phone" !in bobs.map { it.installationId }, "the longest unseen gave way")

        now += DeviceRegistry.MAX_UNSEEN_MS + 1
        register("carol", "carol-tablet")
        pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
        assertEquals(setOf("carol-tablet"), recipients(), "60 days unseen: no messages")
    }

    @Test
    fun registrationsAreChecked() {
        setup()
        assertEquals(400, call("PUT", "/me/devices/x", "bob", KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration("token-0123456789abcdef"))).status, "short id")
        assertEquals(400, call("PUT", "/me/devices/bob-phone", "bob", KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration("has spaces in it, not a token"))).status)
    }

    @Test
    fun aNewMemberJoiningTellsTheBandButNotTheirOwnPhone() {
        setup()
        pushFrom("alice", AddMember("m-erin", "Erin"), installation = "alice-phone", seq = 10)
        sender.sent.clear()
        // Erin registered while in another band of hers (her file is there), then joins from her phone.
        call("POST", "/bands", "erin", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band2", "Larks", "PLN", "m-erin2", "Erin")))
        register("erin", "erin-phone")
        register("erin", "erin-tablet")
        val code = KapeluszJson.decodeFromString(
            InviteInfo.serializer(),
            call("POST", "/bands/band1/invites", "alice", KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest("m-erin", Role.MEMBER))).body!!,
        ).code
        assertEquals(200, call("POST", "/invites/$code/accept", "erin", query = from("erin-phone")).status)
        assertEquals(setOf("alice-phone", "bob-phone", "bob-tablet", "carol-phone", "erin-tablet"), recipients())
    }

    @Test
    fun aMemberUnlinkedByALeaderHearsOfIt() {
        setup()
        pushFrom("alice", UnlinkUser("m-carol"), installation = "alice-phone", seq = 10)
        assertTrue("carol-phone" in recipients())
    }

    @Test
    fun anotherUsersInstallationIdDoesNotSilenceTheirDevice() {
        setup()
        pushFrom("bob", RenameBand("Owls"), installation = "carol-phone", seq = 10)
        assertTrue("carol-phone" in recipients(), "only the caller's own device is skipped")
    }

    @Test
    fun withoutABandNothingIsStoredAndTheAppIsToldNoMessagesCome() {
        setup()
        assertEquals(false, register("frank", "frank-phone"))
        assertNull(store.get("users/frank-devices.json"))
        assertEquals(true, register("bob", "bob-phone"))
        sender.ready = false
        assertEquals(false, register("bob", "bob-phone"), "no key: the app looks for news itself")
        pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
        assertEquals(emptyList(), sender.sent)
    }

    @Test
    fun signingOutKeepsAFileAnotherDeviceWroteMeanwhile() {
        setup()
        assertEquals(204, call("DELETE", "/me/devices/carol-phone", "carol").status)
        val left = KapeluszJson.decodeFromString(StoredDevices.serializer(), store.get("users/carol-devices.json")!!.content).devices
        assertEquals(emptyList(), left, "emptied, not deleted, so a registration racing it isn't lost")
    }

    @Test
    fun leavingTheLastBandForgetsTheDevices() {
        setup()
        call("POST", "/bands/band1/leave", "carol", query = from("carol-phone"))
        assertNull(store.get("users/carol-devices.json"))
    }
}
