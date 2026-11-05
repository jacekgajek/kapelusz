package com.jacekgajek.kapelusz.app.intents

import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.model.AttachmentOwner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class IntentInboxTest :
    ExpectSpec({
        val link = AppIntent.OpenEvent("e1")
        val join = AppIntent.Join("ABC123")
        val share = AppIntent.ShareFiles(SharedFiles(emptyList(), emptyList()))

        expect("keeps one of a kind in the order offered") {
            val inbox = IntentInbox()
            inbox.offer(link)
            inbox.offer(join)
            inbox.pending.value shouldBe listOf(link, join)
            val newer = AppIntent.Join("XYZ789")
            inbox.offer(newer)
            withClue("a newer join link replaces the one waiting") { inbox.pending.value shouldBe listOf(link, newer) }
            inbox.take(join)
            withClue("taking what isn't there changes nothing") { inbox.pending.value shouldBe listOf(link, newer) }
            inbox.take(newer)
            inbox.take(link)
            inbox.pending.value.shouldBeEmpty()
        }

        expect("a carry is taken once by its id and a new share drops those not taken") {
            val inbox = IntentInbox()
            val carry = Carry.Attached(AttachmentOwner.OfEvent("e1"), listOf("a1"), failed = 0)
            val id = inbox.hold(carry)
            inbox.takeCarry(id) shouldBe carry
            withClue("once") { inbox.takeCarry(id).shouldBeNull() }
            val forgotten = inbox.hold(Carry.NewCost(emptyList()))
            inbox.offer(share)
            withClue("files handed to a form earlier must not turn up there later") { inbox.takeCarry(forgotten).shouldBeNull() }
        }

        expect("signing out drops what was resolved for the user and their shared files, and keeps links") {
            val inbox = IntentInbox()
            val resolved = AppIntent.OpenEvent("e2", bandId = "b1", section = EventSection.FILES)
            val held = inbox.hold(Carry.NewCollected(emptyList()))
            listOf(join, resolved, share).forEach(inbox::offer)

            inbox.signedOut()
            withClue("the files are the one who shared them's, not the next user's") { inbox.pending.value shouldBe listOf(join) }
            inbox.takeCarry(held).shouldBeNull()
            val later = IntentInbox()
            later.offer(link)
            later.signedOut()
            withClue("an event link not resolved yet waits for the next user") { later.pending.value shouldBe listOf(link) }
        }
    })
