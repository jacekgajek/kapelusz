package com.jacekgajek.kapelusz.server.bands

import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.server.attachments.AttachmentFiles
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.devices.SenderDevice
import com.jacekgajek.kapelusz.server.history.HistoryLog
import com.jacekgajek.kapelusz.server.invites.Invites
import com.jacekgajek.kapelusz.server.links.EventPointers
import com.jacekgajek.kapelusz.server.messaging.SyncMessenger
import com.jacekgajek.kapelusz.server.messaging.messagedUsers
import com.jacekgajek.kapelusz.server.users.UserRecords

/**
 * Everything that must follow a committed change of a band's state (REFACTORING.md 5.4), in one place and one order,
 * each step on its own so that one failing leaves the others done. The state is already saved when [after] runs: a
 * failure here must not make the client retry the change.
 */
internal class BandChanged(
    private val users: UserRecords,
    private val invites: Invites,
    private val pointers: EventPointers,
    private val attachments: AttachmentFiles,
    private val deletions: BandDeletions,
    private val history: HistoryLog,
    private val messenger: SyncMessenger,
) {
    /**
     * [before] became [after] by [accepted] from [user] (with [changes] recorded for undo), from the device [from]. In
     * this order: the invites of members unlinked or gone go (no leftover code links someone a leader unlinked); the
     * events' link pointers follow the events; the files of attachments that are gone; the list of bands waiting to be
     * deleted; the history entry; then the sync message to everyone in the band before or after, but the device that
     * made the change (ITERATION_7 §2.2); and last the band lists of users whose account was unlinked, whose devices
     * still got that message (ITERATION_6 §11a) before they are forgotten.
     */
    fun after(
        user: VerifiedUser,
        bandId: String,
        before: Band,
        after: Band,
        accepted: List<OpEnvelope>,
        changes: Map<String, BandChange>,
        from: SenderDevice?,
    ) {
        // A member whose account was unlinked, or who is gone (deleted, also by a redo), keeps no invite.
        val unlinkedMembers = accepted.map { it.op }.mapNotNull { (it as? UnlinkUser)?.memberId }
        val goneMembers = before.members.map { it.id } - after.members.map { it.id }.toSet()
        (unlinkedMembers + goneMembers).distinct().forEach { memberId -> runCatching { invites.revokeFor(bandId, memberId) } }
        // New events (also one brought back by an undo): where their links find them; deleted ones: not anymore.
        val eventsBefore = before.events.map { it.id }.toSet()
        val eventsAfter = after.events.map { it.id }.toSet()
        pointers.pointTo(bandId, eventsAfter - eventsBefore)
        pointers.forget(bandId, eventsBefore - eventsAfter)
        // Files of attachments that are gone (removed, or what they belonged to deleted); versioning keeps them for 30 days.
        runCatching { attachments.deleteRemoved(bandId, before, after) }
            .onFailure { System.err.println("Could not delete attachment files of band $bandId: $it") }
        // A deletion asked for, or kept by a leader (ITERATION_7 §9), changes the list of bands waiting.
        if (after.deletion != before.deletion) {
            runCatching { deletions.note(bandId) }.onFailure { System.err.println("Could not list band $bandId for deletion: $it") }
        }
        // The author's member as they were before, or as they are now when this change linked them (an accepted invite).
        val memberId = (before.memberOfUser(user.userId) ?: after.memberOfUser(user.userId))?.id
        runCatching { history.append(user, memberId, bandId, accepted, changes) }
            .onFailure { System.err.println("Could not append history for band $bandId: $it") }
        if (after != before) runCatching { messenger.bandChanged(bandId, before.messagedUsers() + after.messagedUsers(), from) }
        // Someone's account unlinked (by a leader, deleting them, or leaving, ITERATION_7 §3.1): as if they had left.
        val unlinked = before.members.mapNotNull { it.userId }.toSet() - after.members.mapNotNull { it.userId }.toSet()
        unlinked.forEach { userId ->
            runCatching { users.removeBandFromUser(userId, bandId) }
            runCatching { users.forgetIfNoBands(userId) }
        }
    }
}
