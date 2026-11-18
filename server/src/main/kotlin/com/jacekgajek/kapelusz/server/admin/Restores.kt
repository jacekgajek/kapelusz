package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.allAttachments
import com.jacekgajek.kapelusz.ops.RestoreBand
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.bands.BandAccess
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.devices.DeviceRegistry
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.history.HistoryLog
import com.jacekgajek.kapelusz.server.links.EventPointers
import com.jacekgajek.kapelusz.server.store.Blob
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.BlobVersion
import com.jacekgajek.kapelusz.server.store.HistoryStore
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import com.jacekgajek.kapelusz.server.store.RestoreRecord
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.users.UserRecords
import kotlinx.serialization.SerializationException

/**
 * Puts a band back as it was at an earlier time.
 *
 * The bucket keeps every copy of a band's file for 30 days. A restore takes one of these copies and stores it again.
 * This is how the admin panel repairs a band. It never edits a band's data by hand.
 *
 * Two things do not come from the copy. They stay as they are now:
 * - The sync numbers (`lastSeq`, `recentRejections`). A phone may send an old push again. With the old numbers that
 *   push would be applied a second time.
 * - A deletion that was asked for. The copy may be from a time when the band was to be deleted. That time is long
 *   past, so the band would be deleted at once.
 *
 * After the band is stored, four records beside it are updated: the members' band lists, the events' link pointers,
 * the band's history, and a record of the restore. Each is tried on its own. One that fails does not stop the others.
 *
 * Attachment files and invites are not touched.
 */
internal class Restores(private val store: BlobStore, private val clock: () -> Long, historyStore: HistoryStore?) {
    // `whenDue` is not set, so loading a band here never deletes it.
    private val bands = BandAccess(store, clock)
    private val users = UserRecords(store, bands, DeviceRegistry(store, clock))
    private val pointers = EventPointers(store, bands)
    private val history = historyStore?.let { HistoryLog(it, clock) }

    /** The copies of the band's file, newest first. Also the restores made so far. */
    fun versions(bandId: String): VersionList {
        // One more than is shown, to know whether there are older ones.
        val copies = store.versions(StorageKeys.band(bandId), MAX_VERSIONS + 1)
        if (copies.isEmpty()) throw notFound("No such band")
        val restores = store.entries(StorageKeys.restoresPrefix(bandId)).mapNotNull { record(it.key) }
            .sortedByDescending { it.at }
            .map { RestoreRow(it.at, it.stateOf, it.versionId, it.replacedVersionId, it.problems) }
        val shown = copies.take(MAX_VERSIONS).map { VersionRow(it.id, it.modified, it.size, it.latest) }
        return VersionList(shown, more = copies.size > MAX_VERSIONS, restores = restores)
    }

    /** The restore record at [key]. Null when it cannot be read. */
    private fun record(key: String): RestoreRecord? {
        val read = runCatching { store.get(key)?.let { decode(RestoreRecord.serializer(), it.content) } }
        return read.getOrNull()
    }

    /** Says what would change if the copy [versionId] were put back. Changes nothing. */
    fun preview(bandId: String, versionId: String): RestorePreview {
        val plan = plan(bandId, versionId)
        return RestorePreview(
            versionId = versionId,
            stateOf = plan.copy.modified,
            currentEtag = plan.current.etag,
            name = plan.name,
            currentReadable = plan.now != null,
            latest = plan.copy.latest,
            members = diff(plan.now?.members, plan.restored.members, { it.id }, { it.name }),
            events = diff(plan.now?.events, plan.restored.events, { it.id }, { it.name }),
            costs = diff(plan.now?.costs, plan.restored.costs, { it.id }, { null }),
            fundEntries = diff(plan.now?.fundEntries, plan.restored.fundEntries, { it.id }, { null }),
            settingsChange = plan.now != null && plan.now.withoutLists() != plan.restored.withoutLists(),
            gainAccess = (plan.activeAfter - plan.activeBefore).mapNotNull { userId -> plan.restored.memberOfUser(userId)?.name },
            loseAccess = (plan.activeBefore - plan.activeAfter).mapNotNull { userId -> plan.now?.memberOfUser(userId)?.name },
            missingFiles = missingFiles(bandId, plan.restored),
            deleteAt = plan.restored.deletion?.deleteAt,
        )
    }

    /**
     * Counts the attachments in [restored] whose file is not stored.
     *
     * When an attachment is removed, its file is deleted. A copy from before that brings the attachment back, but
     * not the file. The preview warns about it.
     */
    private fun missingFiles(bandId: String, restored: Band): Int {
        val stored = store.entries(StorageKeys.attachmentsPrefix(bandId)).map { it.key }.toSet()
        return restored.allAttachments().count { StorageKeys.attachment(bandId, it) !in stored }
    }

    /**
     * Puts the copy named in [request] back. [by] is the admin who asked.
     *
     * The request must carry two things from the preview. The ETag shows the band has not changed since the admin
     * looked. The typed name shows the admin knows which band this is.
     */
    fun restore(bandId: String, request: RestoreRequest, by: VerifiedUser): RestoreDone {
        val plan = plan(bandId, request.versionId)
        val refusal = when {
            plan.copy.latest -> "This copy is what is stored now"
            request.etag != plan.current.etag -> "The band was changed since you looked"
            request.name.trim() != plan.name.trim() -> "The name typed is not the band's"
            else -> null
        }
        if (refusal != null) throw conflict(refusal)
        try {
            store.put(StorageKeys.band(bandId), encode(Band.serializer(), plan.restored), ifMatch = plan.current.etag)
        } catch (ignored: PreconditionFailed) {
            throw conflict("The band was changed just now")
        }
        val now = clock()
        println("ADMIN ${by.userId} put band $bandId back as it was at ${plan.copy.modified} (copy ${request.versionId})")
        val problems = updateSideRecords(bandId, plan, now)
        // The newest copy is the one just written. The one before it is what was replaced.
        val replaced = runCatching { store.versions(StorageKeys.band(bandId), 2).firstOrNull { !it.latest }?.id }.getOrNull()
        val record = RestoreRecord(now, by.userId, request.versionId, plan.copy.modified, replaced, plan.current.etag, problems)
        val recorded = runCatching { store.put(StorageKeys.restore(bandId, now), encode(RestoreRecord.serializer(), record)) }
        return RestoreDone(problems + listOfNotNull("the record of this restore".takeIf { recorded.isFailure }))
    }

    /**
     * Updates the records beside the band after it was put back. Returns what failed, in words. Throws nothing:
     * the band is already stored, so a failure here must not look like a failed restore.
     */
    private fun updateSideRecords(bandId: String, plan: Plan, now: Long): List<String> {
        val steps = mapOf<String, () -> Unit>(
            "the band lists of members who are in the band again" to { each(plan.gained) { users.addBandToUser(it, bandId) } },
            "the band lists of members who are no longer in the band" to { each(plan.lost) { users.removeBandFromUser(it, bandId) } },
            "the link pointers of events" to {
                val before = plan.now?.events.orEmpty().map { it.id }.toSet()
                val after = plan.restored.events.map { it.id }.toSet()
                pointers.pointTo(bandId, after - before)
                pointers.forget(bandId, before - after)
            },
            "the entry in the band's history" to {
                val entry = OpEnvelope("restore-$now", "server", 0, now, RestoreBand(plan.copy.modified))
                // No member did this. The entry has no member, and "support" as its user.
                checkNotNull(history) { "This server keeps no history" }.append(SUPPORT_USER, null, bandId, listOf(entry), emptyMap())
            },
        )
        return steps.filterValues { step -> failed(bandId, step) }.keys.toList()
    }

    /** Runs [update] for every user. One user that fails does not stop the others. Fails at the end if any failed. */
    private fun each(userIds: Set<String>, update: (String) -> Unit) {
        val failures = userIds.mapNotNull { userId -> runCatching { update(userId) }.exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    /** Runs [step]. True when it failed; the failure goes to the log. */
    private fun failed(bandId: String, step: () -> Unit): Boolean {
        val tried = runCatching(step)
        tried.onFailure { System.err.println("Restore of band $bandId: a side record was not written: $it") }
        return tried.isFailure
    }

    /**
     * Everything a restore needs.
     *
     * [current] is the band's file as stored now. [now] is the band in it, or null when the file cannot be read.
     * [copy] is the copy to put back. [restored] is the band that will be stored.
     */
    private class Plan(val current: Blob, val now: Band?, val copy: BlobVersion, val restored: Band) {
        private val before = now?.members.orEmpty().mapNotNull { it.userId }.toSet()
        private val after = restored.members.mapNotNull { it.userId }.toSet()

        /** Users who are not linked to a member now, but will be after the restore. Their band lists get the band. */
        val gained = after - before

        /** Users who are linked to a member now, but will not be after the restore. Their band lists lose the band. */
        val lost = before - after

        /**
         * Users who can open the band now, and users who can after the restore.
         *
         * This is not the same as being linked. An archived member stays linked, but the API does not let them in.
         */
        val activeBefore = active(now)
        val activeAfter = active(restored)

        private fun active(band: Band?) = band?.members.orEmpty().filter { !it.archived }.mapNotNull { it.userId }.toSet()

        /** The name the admin must type. It is the band's name now. If the file cannot be read, it is the copy's. */
        val name = (now ?: restored).name
    }

    private fun plan(bandId: String, versionId: String): Plan {
        val key = StorageKeys.band(bandId)
        val copy = store.versions(key, MAX_VERSIONS).firstOrNull { it.id == versionId }
        val current = store.entries(key).firstOrNull { it.key == key }?.let { store.get(key) }
        if (copy == null || current == null) throw notFound(if (current == null) "No such band" else "No such copy of this band")
        val old = store.getVersion(key, versionId)?.let { band(it.content) }?.takeIf { it.id == bandId }
            ?: throw ApiError(422, UNREADABLE, "This copy cannot be read as this band")
        // The file as it is now may not be a band. That is when a restore is needed most. Then all comes from the copy.
        val now = band(current.content)
        val restored = old.copy(
            lastSeq = newest(old.lastSeq, now?.lastSeq.orEmpty()),
            recentRejections = now?.recentRejections ?: old.recentRejections,
            deletion = now?.deletion,
        )
        return Plan(current, now, copy, restored)
    }

    /** For each client, the higher of its two sync numbers. */
    private fun newest(old: Map<String, Long>, now: Map<String, Long>): Map<String, Long> {
        val clients = old.keys + now.keys
        return clients.associateWith { client -> maxOf(old[client] ?: 0, now[client] ?: 0) }
    }

    /** The band in [content]. Null when it is not a band. */
    private fun band(content: String): Band? = try {
        decode(Band.serializer(), content)
    } catch (ignored: SerializationException) {
        null
    }

    /**
     * The band's own settings only. The lists are left out because [diff] compares them. The sync numbers and the
     * deletion are left out because a restore does not change them.
     */
    private fun Band.withoutLists() = copy(
        members = emptyList(),
        events = emptyList(),
        costs = emptyList(),
        fundEntries = emptyList(),
        lastSeq = emptyMap(),
        recentRejections = emptyMap(),
        deletion = null,
    )

    /**
     * Compares one list before and after a restore.
     *
     * [before] is the list now, or null when the band cannot be read. [after] is the list in the copy.
     */
    private fun <T> diff(before: List<T>?, after: List<T>, id: (T) -> String, name: (T) -> String?): PartDiff {
        val now = before.orEmpty().associateBy(id)
        val then = after.associateBy(id)
        val back = then.keys - now.keys
        val gone = now.keys - then.keys
        return PartDiff(
            back = back.size,
            gone = gone.size,
            changed = then.keys.intersect(now.keys).count { then[it] != now[it] },
            backNames = back.mapNotNull { then[it]?.let(name) }.take(MAX_NAMES),
            goneNames = gone.mapNotNull { now[it]?.let(name) }.take(MAX_NAMES),
        )
    }

    private fun notFound(message: String) = ApiError(404, ApiErrors.NOT_FOUND, message)

    private fun conflict(message: String) = ApiError(409, ApiErrors.CONFLICT, message)

    companion object {
        /** How many copies are listed. A band's file is written at every change, so a busy band has many. */
        const val MAX_VERSIONS = 200

        /** How many names a preview lists for one kind of thing. */
        const val MAX_NAMES = 20

        /** The error code for a copy that is not a band. */
        const val UNREADABLE = "unreadable"

        /** The user that the history names for a restore. It is no user of the app. */
        val SUPPORT_USER = VerifiedUser("support", null, null)
    }
}
