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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

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

/** The harness of [SyncMessagesTest]: an API whose messages go to a [FakeSender]. */
private class SyncApi : ApiHarness(messageSender = FakeSender()) {
    val sender get() = messageSender as FakeSender

    /** The request comes from the device [installation]. */
    fun from(installation: String) = mapOf("installation" to installation)

    /** Whether messages will come, as the server answers the registration. */
    fun register(user: String, installation: String, token: String = "token-$installation-0123456789"): Boolean {
        val response = call(
            "PUT",
            "/me/devices/$installation",
            user,
            KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration(token)),
        )
        response.status shouldBe 200
        return KapeluszJson.decodeFromString(DeviceRegistrationResponse.serializer(), response.body!!).messaging
    }

    /** A push from the device [installation], with its own sequence [seq]. */
    fun pushFrom(user: String, vararg ops: com.jacekgajek.kapelusz.ops.Operation, installation: String, seq: Long) {
        val envelopes = ops.mapIndexed { i, op -> OpEnvelope("$user-$seq-$i", "phone", seq + i, 1, op) }
        val body = KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes))
        call("POST", "/bands/band1/ops", user, body, query = from(installation)).status shouldBe 200
    }

    /** Alice owns band1; Bob and Carol joined; Dan is a member without the app. Each has devices registered. */
    fun setup() {
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

    fun recipients() = sender.sent.map { it.first.removePrefix("token-").removeSuffix("-0123456789") }.toSet()
}

/** Sync messages: who gets them, and the device registry behind them. */
class SyncMessagesTest :
    ExpectSpec({
        expect("a change reaches the other devices of the band but not the one that made it") {
            with(SyncApi()) {
                setup()
                pushFrom("bob", RenameBand("Owls"), installation = "bob-phone", seq = 10)
                recipients() shouldBe setOf("alice-phone", "bob-tablet", "carol-phone")
                withClue("only the band's id") { sender.sent.forAll { it.second shouldBe "band1" } }
            }
        }

        expect("a refused or empty push sends nothing") {
            with(SyncApi()) {
                setup()
                // A member id that is taken: the push is answered, the change refused.
                pushFrom("alice", AddMember("m-bob", "Bob again"), installation = "alice-phone", seq = 10)
                withClue("refused: nothing to tell") { sender.sent.shouldBeEmpty() }
            }
        }

        expect("a member moved to former members hears of it once and then no more") {
            with(SyncApi()) {
                setup()
                pushFrom("alice", SetMemberArchived("m-carol", true), installation = "alice-phone", seq = 10)
                withClue("learns at once that the band is gone for her") { recipients() shouldContain "carol-phone" }
                sender.sent.clear()
                pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 20)
                recipients() shouldBe setOf("bob-phone", "bob-tablet")
            }
        }

        expect("joining and leaving tell the band") {
            with(SyncApi()) {
                setup()
                call("POST", "/bands/band1/leave", "bob", query = from("bob-phone"))
                withClue("Bob's tablet lets go of the band too") { recipients() shouldBe setOf("alice-phone", "bob-tablet", "carol-phone") }
            }
        }

        expect("deleting tells everyone") {
            with(SyncApi()) {
                setup()
                call("DELETE", "/bands/band1", "alice", query = from("alice-phone"))
                recipients() shouldBe setOf("bob-phone", "bob-tablet", "carol-phone")
            }
        }

        expect("an invalid token is forgotten") {
            with(SyncApi()) {
                setup()
                sender.invalid += "token-bob-tablet-0123456789"
                pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
                val bobs = KapeluszJson.decodeFromString(StoredDevices.serializer(), store.get("users/bob-devices.json")!!.content).devices
                bobs.map { it.installationId } shouldBe listOf("bob-phone")
            }
        }

        expect("signing out one device or everywhere") {
            with(SyncApi()) {
                setup()
                call("DELETE", "/me/devices/bob-tablet", "bob").status shouldBe 204
                pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
                recipients() shouldBe setOf("bob-phone", "carol-phone")
                sender.sent.clear()
                call("POST", "/sessions/revoke", "carol").status shouldBe 204
                store.get("users/carol-devices.json").shouldBeNull()
            }
        }

        expect("the registry keeps ten devices and forgets unseen ones") {
            with(SyncApi()) {
                setup()
                repeat(12) { i ->
                    now += 1000
                    register("bob", "bob-device-$i")
                }
                val bobs = KapeluszJson.decodeFromString(StoredDevices.serializer(), store.get("users/bob-devices.json")!!.content).devices
                bobs.size shouldBe DeviceRegistry.MAX_DEVICES
                withClue("the longest unseen gave way") {
                    ("bob-device-11" in bobs.map { it.installationId } && "bob-phone" !in bobs.map { it.installationId }) shouldBe true
                }

                now += DeviceRegistry.MAX_UNSEEN_MS + 1
                register("carol", "carol-tablet")
                pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
                withClue("60 days unseen: no messages") { recipients() shouldBe setOf("carol-tablet") }
            }
        }

        expect("registrations are checked") {
            with(SyncApi()) {
                setup()
                withClue("short id") {
                    call(
                        "PUT",
                        "/me/devices/x",
                        "bob",
                        KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration("token-0123456789abcdef")),
                    ).status shouldBe 400
                }
                call(
                    "PUT",
                    "/me/devices/bob-phone",
                    "bob",
                    KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration("has spaces in it, not a token")),
                ).status shouldBe 400
            }
        }

        expect("a new member joining tells the band but not their own phone") {
            with(SyncApi()) {
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
                call("POST", "/invites/$code/accept", "erin", query = from("erin-phone")).status shouldBe 200
                recipients() shouldBe setOf("alice-phone", "bob-phone", "bob-tablet", "carol-phone", "erin-tablet")
            }
        }

        expect("a member unlinked by a leader hears of it") {
            with(SyncApi()) {
                setup()
                pushFrom("alice", UnlinkUser("m-carol"), installation = "alice-phone", seq = 10)
                recipients() shouldContain "carol-phone"
            }
        }

        expect("another user's installation id does not silence their device") {
            with(SyncApi()) {
                setup()
                pushFrom("bob", RenameBand("Owls"), installation = "carol-phone", seq = 10)
                withClue("only the caller's own device is skipped") { recipients() shouldContain "carol-phone" }
            }
        }

        expect("without a band nothing is stored and the app is told no messages come") {
            with(SyncApi()) {
                setup()
                register("frank", "frank-phone") shouldBe false
                store.get("users/frank-devices.json").shouldBeNull()
                register("bob", "bob-phone") shouldBe true
                sender.ready = false
                withClue("no key: the app looks for news itself") { register("bob", "bob-phone") shouldBe false }
                pushFrom("alice", RenameBand("Owls"), installation = "alice-phone", seq = 10)
                sender.sent.shouldBeEmpty()
            }
        }

        expect("signing out keeps a file another device wrote meanwhile") {
            with(SyncApi()) {
                setup()
                call("DELETE", "/me/devices/carol-phone", "carol").status shouldBe 204
                val left =
                    KapeluszJson.decodeFromString(StoredDevices.serializer(), store.get("users/carol-devices.json")!!.content).devices
                withClue("emptied, not deleted, so a registration racing it isn't lost") { left.shouldBeEmpty() }
            }
        }

        expect("leaving the last band forgets the devices") {
            with(SyncApi()) {
                setup()
                call("POST", "/bands/band1/leave", "carol", query = from("carol-phone"))
                store.get("users/carol-devices.json").shouldBeNull()
            }
        }
    })
