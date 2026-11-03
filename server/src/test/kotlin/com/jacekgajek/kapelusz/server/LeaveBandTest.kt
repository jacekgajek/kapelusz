package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.server.store.UserRecord
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

private class LeaveBandApi : ApiHarness() {
    /** Alice owns the band; Bob joined it and recorded a cost. */
    fun setup() {
        createBand()
        val ops = listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob")))
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
        call("POST", "/invites/${inviteCode("m-bob")}/accept", "bob").status shouldBe 200
        val cost = listOf(OpEnvelope("op2", "phone", 1, 1, AddCost("c1", "m-bob", Money(5000), LocalDate(2026, 9, 1))))
        call("POST", "/bands/band1/ops", "bob", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(cost)))
    }

    fun band() = KapeluszJson.decodeFromString(BandStateResponse.serializer(), call("GET", "/bands/band1/state", "alice").body!!).band

    /** A calendar link and a Google photo kept for [user], as signing in and subscribing leave them. */
    fun subscribeAndGivePhoto(user: String) {
        call(
            "POST",
            "/me/calendar",
            user,
            KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), CalendarSubscriptionChange()),
        )
        val blob = store.get("users/$user.json")!!
        val record = KapeluszJson.decodeFromString(UserRecord.serializer(), blob.content)
        store.put(
            "users/$user.json",
            KapeluszJson.encodeToString(
                UserRecord.serializer(),
                record.copy(pictureUrl = "https://lh3.googleusercontent.com/a/$user"),
            ),
            ifMatch = blob.etag,
        )
        store.get("users/$user-calendar.json").shouldNotBeNull()
    }
}

class LeaveBandTest :
    ExpectSpec({

        expect("a member leaves and their entry stays") {
            with(LeaveBandApi()) {
                setup()
                call("POST", "/bands/band1/leave", "bob").status shouldBe 204

                val bob = band().member("m-bob")!!
                withClue("the account is unlinked") { bob.userId.shouldBeNull() }
                withClue("what they recorded stays") { band().costs.count { it.memberId == "m-bob" } shouldBe 1 }
                KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", "bob").body!!).bands.shouldBeEmpty()
                call("GET", "/bands/band1/state", "bob").body!! shouldContain ApiErrors.BAND_NOT_FOUND
                withClue("a second time: not a member any more") { call("POST", "/bands/band1/leave", "bob").status shouldBe 404 }
                // A new invite brings them back.
                call("POST", "/invites/${inviteCode("m-bob")}/accept", "bob").status shouldBe 200
                band().member("m-bob")!!.userId shouldBe "bob"
            }
        }

        expect("the owner cannot leave") {
            with(LeaveBandApi()) {
                setup()
                val refused = call("POST", "/bands/band1/leave", "alice")
                refused.status shouldBe 409
                refused.body!! shouldContain ApiErrors.OWNER_CANNOT_LEAVE
                band().owner?.userId shouldBe "alice"
            }
        }

        expect("the history names who left") {
            with(LeaveBandApi()) {
                setup()
                call("POST", "/bands/band1/leave", "bob").status shouldBe 204
                val entry = history.bands["band1"]!!.values.map { KapeluszJson.decodeFromString(HistoryEntry.serializer(), it) }
                    .single { it.ops.single().op == UnlinkUser("m-bob") }
                withClue("the author is who left, not unknown") { entry.memberId shouldBe "m-bob" }
            }
        }

        expect("leaving the last band leaves only the account id") {
            with(LeaveBandApi()) {
                setup()
                subscribeAndGivePhoto("bob")
                withClue("from accepting the invite") { store.get("users/bob-join-attempts.json").shouldNotBeNull() }
                call("POST", "/bands/band1/leave", "bob").status shouldBe 204

                val record = KapeluszJson.decodeFromString(UserRecord.serializer(), store.get("users/bob.json")!!.content)
                withClue("no bands, no photo") { record shouldBe UserRecord("bob", sessionsValidFrom = record.sessionsValidFrom) }
                store.get("users/bob-calendar.json").shouldBeNull()
                withClue("the feed link is gone") { store.list("calendar/").shouldBeEmpty() }
                store.get("users/bob-join-attempts.json").shouldBeNull()
            }
        }

        expect("leaving one of two bands keeps the account as it is") {
            with(LeaveBandApi()) {
                setup()
                call("POST", "/bands", "bob", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band2", "Larks", "PLN", "m-bob2", "Bob")))
                subscribeAndGivePhoto("bob")
                val before = store.get("users/bob.json")!!.content
                call("POST", "/bands/band1/leave", "bob").status shouldBe 204

                val record = KapeluszJson.decodeFromString(UserRecord.serializer(), store.get("users/bob.json")!!.content)
                record shouldBe KapeluszJson.decodeFromString(UserRecord.serializer(), before).copy(bandIds = listOf("band2"))
                withClue("the calendar keeps working for band2") { store.get("users/bob-calendar.json").shouldNotBeNull() }
                store.list("calendar/").shouldNotBeEmpty()
            }
        }

        expect("being unlinked by a leader is like leaving") {
            with(LeaveBandApi()) {
                setup()
                subscribeAndGivePhoto("bob")
                val unlink = listOf(OpEnvelope("op9", "phone", 9, 1, UnlinkUser("m-bob")))
                call(
                    "POST",
                    "/bands/band1/ops",
                    "alice",
                    KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(unlink)),
                ).status shouldBe 200

                val record = KapeluszJson.decodeFromString(UserRecord.serializer(), store.get("users/bob.json")!!.content)
                withClue("his last band: only the account id stays") {
                    record shouldBe UserRecord("bob", sessionsValidFrom = record.sessionsValidFrom)
                }
                store.get("users/bob-calendar.json").shouldBeNull()
                KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", "bob").body!!).bands.shouldBeEmpty()
            }
        }

        expect("deleting a member cancels their invites") {
            with(LeaveBandApi()) {
                setup()
                val add = listOf(OpEnvelope("op10", "phone", 10, 1, AddMember("m-carol", "Carol")))
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(add)))
                val code = inviteCode("m-carol")
                val delete = listOf(OpEnvelope("op11", "phone", 11, 1, DeleteMember("m-carol")))
                call(
                    "POST",
                    "/bands/band1/ops",
                    "alice",
                    KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(delete)),
                ).status shouldBe 200
                withClue("no old code comes back with an Undo") { call("GET", "/invites/$code", "dan").status shouldBe 404 }
            }
        }
    })
