package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.rules.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

/** What follows a committed change of a band ([BandChanged.after]), in the order its KDoc promises. */
class BandChangedTest {
    /** Writes down every write, so the order of the consequences can be read off. */
    private class RecordingStore(private val inner: BlobStore, private val log: MutableList<String>) : BlobStore by inner {
        override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
            log += "put $key"
            return inner.put(key, content, ifMatch, ifNoneMatch)
        }

        override fun delete(key: String) {
            log += "delete $key"
            inner.delete(key)
        }
    }

    private class RecordingHistory(private val inner: HistoryStore, private val log: MutableList<String>) : HistoryStore by inner {
        override fun put(bandId: String, records: List<HistoryRecord>) {
            log += "history"
            inner.put(bandId, records)
        }
    }

    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.System, op, "t-${op.hashCode()}", 0) as ApplyResult.Applied).band
    }

    @Test
    fun whatFollowsAChangeHappensInOrderWithTheMessageBeforeTheDevicesAreForgotten() {
        val log = mutableListOf<String>()
        val store = RecordingStore(InMemoryBlobStore(), log)
        val history = RecordingHistory(InMemoryHistoryStore(), log)
        val bands = BandAccess(store) { 1_000 }
        val devices = DeviceRegistry(store) { 1_000 }
        val users = UserRecords(store, bands, devices)
        val invites = Invites(store) { 1_000 }
        val pointers = EventPointers(store, bands)
        val files = AttachmentFiles(store)
        val historyLog = HistoryLog(history) { 1_000 }
        val deletions = BandDeletions(store, { 1_000 }, bands, users, invites, pointers, historyLog, null)
        val messenger = object : SyncMessenger {
            override fun bandChanged(bandId: String, userIds: Collection<String>, from: SenderDevice?) {
                log += "message to ${userIds.toSortedSet()}"
            }
        }
        val changed = BandChanged(users, invites, pointers, files, deletions, historyLog, messenger)

        // Alice owns the band; Bob is linked, has an invite code waiting and a cost with a photo; the gig has a link pointer.
        val photo = Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 3)
        val before = Band.create("band1", "Night Owls", "PLN", "m-alice", "Alice", "alice").apply(
            AddMember("m-bob", "Bob"),
            LinkUser("m-bob", "bob", Role.MEMBER),
            CreateEvent("e1", "Gig", LocalDate(2026, 9, 13)),
            AddCost("c1", "m-bob", Money(5000), LocalDate(2026, 9, 1)),
            AddAttachment("c1", photo),
        )
        store.put(StorageKeys.band("band1"), encode(Band.serializer(), before))
        users.addBandToUser("bob", "band1")
        val code = invites.create("band1", "m-bob", Role.MEMBER, "alice").code
        pointers.pointTo("band1", listOf("e1"))
        log.clear()

        // Alice unlinks Bob, deletes his cost (with its photo) and the gig.
        val unlink = UnlinkUser("m-bob")
        val after = before.apply(unlink, DeleteCost("c1"), DeleteEvent("e1"))
        val accepted = listOf(com.jacekgajek.kapelusz.history.OpEnvelope("op1", "phone", 1, 1, unlink))
        changed.after(VerifiedUser("alice", null, null), "band1", before, after, accepted, emptyMap(), null)

        assertEquals(
            listOf(
                "delete invites/$code.json",
                "put bands/band1/invites.json",
                "delete events/e1.json",
                "delete bands/band1/attachments/a1.jpg",
                "history",
                "message to [alice, bob]",
                "put users/bob.json",
                "put users/bob.json",
                "delete users/bob-devices.json",
                "delete users/bob-join-attempts.json",
            ),
            log,
        )
    }
}
