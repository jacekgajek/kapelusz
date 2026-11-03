package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import com.jacekgajek.kapelusz.server.bands.BandDeletions
import com.jacekgajek.kapelusz.server.store.ScheduledDeletions
import com.jacekgajek.kapelusz.server.store.StorageKeys
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

private class DeleteBandApi : ApiHarness() {
    /** Alice owns the band, Bob joined it via an invite, Carol has an open invite; a cost has a photo. */
    fun setup(): String {
        createBand()
        push(
            "alice",
            AddMember("m-bob", "Bob"),
            AddMember("m-carol", "Carol"),
            AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)),
            AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 100)),
        )
        store.files += "bands/band1/attachments/a1.jpg"
        fun invite(member: String) = KapeluszJson.decodeFromString(
            InviteInfo.serializer(),
            call(
                "POST",
                "/bands/band1/invites",
                "alice",
                KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(member, Role.LEAD)),
            ).body!!,
        )
        call("POST", "/invites/${invite("m-bob").code}/accept", "bob").status shouldBe 200
        return invite("m-carol").code
    }

    fun state(user: String) = KapeluszJson.decodeFromString(
        BandStateResponse.serializer(),
        call("GET", "/bands/band1/state", user).body!!,
    ).band
}

class DeleteBandTest :
    ExpectSpec({

        expect("the owner asks and the band goes a week later for everyone") {
            with(DeleteBandApi()) {
                val carolsCode = setup()
                withClue("a leader is not enough") { call("DELETE", "/bands/band1", "bob").status shouldBe 403 }
                call("DELETE", "/bands/band1", "alice").status shouldBe 204
                withClue("the band works on, saying when it goes") {
                    state("bob").deletion shouldBe BandDeletion("m-alice", now, now + BandDeletion.DELAY_MS)
                }
                call("DELETE", "/bands/band1", "alice").status shouldBe 204
                withClue("asked again: still the first time") { state("bob").deletion!!.deleteAt shouldBe now + BandDeletion.DELAY_MS }

                now += BandDeletion.DELAY_MS
                // Loading it is what deletes it.
                call("GET", "/bands/band1/state", "bob")
                (store.list("bands/band1/") + store.files.filter { it.startsWith("bands/band1/") }) shouldBe
                    listOf("bands/band1/deleted.json")
                withClue("open invites are gone") { call("GET", "/invites/$carolsCode", "carol").status shouldBe 404 }
                for (user in listOf("alice", "bob")) {
                    KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", user).body!!).bands.shouldBeEmpty()
                    val gone = call("GET", "/bands/band1/state", user)
                    gone.status shouldBe 404
                    withClue("former members learn it was deleted") { gone.body!! shouldContain ApiErrors.BAND_DELETED }
                    gone.body!! shouldContain "by Alice"
                }
                withClue("others learn nothing") {
                    call("GET", "/bands/band1/state", "mallory").body!! shouldContain ApiErrors.BAND_NOT_FOUND
                }
            }
        }

        expect("a leader keeps the band and only the server sets the time") {
            with(DeleteBandApi()) {
                setup()
                call("DELETE", "/bands/band1", "alice")
                val forged = push("alice", ScheduleBandDeletion(BandDeletion("m-alice", 0, 0)))
                withClue("no deletion pushed with a time of one's own") { forged.body!! shouldContain "ROLE_REQUIRED" }
                push("bob", CancelBandDeletion)
                state("bob").deletion.shouldBeNull()
                now += BandDeletion.DELAY_MS
                withClue("kept") { call("GET", "/bands/band1/state", "bob").status shouldBe 200 }
                KapeluszJson.decodeFromString(
                    ScheduledDeletions.serializer(),
                    store.get(StorageKeys.SCHEDULED_DELETIONS)!!.content,
                ) shouldBe ScheduledDeletions()
            }
        }

        expect("a band nobody opens goes too on the hourly sweep") {
            with(DeleteBandApi()) {
                setup()
                call("DELETE", "/bands/band1", "alice")
                now += BandDeletion.DELAY_MS + BandDeletions.SWEEP_EVERY_MS
                // Any request, about anything.
                call("GET", "/me", "mallory")
                store.list("bands/band1/") shouldBe listOf("bands/band1/deleted.json")
                KapeluszJson.decodeFromString(
                    ScheduledDeletions.serializer(),
                    store.get(StorageKeys.SCHEDULED_DELETIONS)!!.content,
                ) shouldBe ScheduledDeletions()
            }
        }

        expect("the list of bands waiting follows the band") {
            with(DeleteBandApi()) {
                setup()
                fun waiting() = KapeluszJson.decodeFromString(ScheduledDeletions.serializer(), store.get(StorageKeys.SCHEDULED_DELETIONS)!!.content).bands
                call("DELETE", "/bands/band1", "alice")
                push("bob", CancelBandDeletion)
                push("alice", CancelBandDeletion)
                withClue("kept twice: the second has nothing to do, and isn't refused") { state("bob").deletion.shouldBeNull() }
                now += 1000
                call("DELETE", "/bands/band1", "alice")
                withClue("asked again: the new time") { waiting() shouldBe mapOf("band1" to now + BandDeletion.DELAY_MS) }
            }
        }
    })
