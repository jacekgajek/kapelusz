package com.jacekgajek.kapelusz.app.intents

import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.model.AttachmentOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntentInboxTest {
    private val link = AppIntent.OpenEvent("e1")
    private val join = AppIntent.Join("ABC123")
    private val share = AppIntent.ShareFiles(SharedFiles(emptyList(), emptyList()))

    @Test
    fun keepsOneOfAKindInTheOrderOffered() {
        val inbox = IntentInbox()
        inbox.offer(link)
        inbox.offer(join)
        assertEquals(listOf(link, join), inbox.pending.value)
        val newer = AppIntent.Join("XYZ789")
        inbox.offer(newer)
        assertEquals(listOf(link, newer), inbox.pending.value, "a newer join link replaces the one waiting")
        inbox.take(join)
        assertEquals(listOf(link, newer), inbox.pending.value, "taking what isn't there changes nothing")
        inbox.take(newer)
        inbox.take(link)
        assertEquals(emptyList(), inbox.pending.value)
    }

    @Test
    fun aCarryIsTakenOnceByItsIdAndANewShareDropsThoseNotTaken() {
        val inbox = IntentInbox()
        val carry = Carry.Attached(AttachmentOwner.OfEvent("e1"), listOf("a1"), failed = 0)
        val id = inbox.hold(carry)
        assertEquals(carry, inbox.takeCarry(id))
        assertNull(inbox.takeCarry(id), "once")
        val forgotten = inbox.hold(Carry.NewCost(emptyList()))
        inbox.offer(share)
        assertNull(inbox.takeCarry(forgotten), "files handed to a form earlier must not turn up there later")
    }

    @Test
    fun signingOutDropsWhatWasResolvedForTheUserAndKeepsLinks() {
        val inbox = IntentInbox()
        val resolved = AppIntent.OpenEvent("e2", bandId = "b1", section = EventSection.FILES)
        val held = inbox.hold(Carry.NewCollected(emptyList()))
        listOf(join, resolved, share).forEach(inbox::offer)
        inbox.signedOut()
        assertEquals(listOf(join, share), inbox.pending.value)
        assertNull(inbox.takeCarry(held))
        val later = IntentInbox()
        later.offer(link)
        later.signedOut()
        assertEquals(listOf(link), later.pending.value, "an event link not resolved yet waits for the next user")
    }
}
