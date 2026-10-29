package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.model.Band
import kotlinx.serialization.Serializable

/**
 * Deleting bands (ITERATION_7 §9): the owner asks, and the band goes a week later unless a leader keeps it. A band
 * whose time has come is deleted when someone next loads it ([BandAccess.load]), or by [sweep], which some request
 * runs at most hourly, from the list of bands waiting ([ScheduledDeletions]), so a band nobody opens goes too.
 */
internal class BandDeletions(
    private val store: BlobStore,
    private val clock: () -> Long,
    private val bands: BandAccess,
    private val users: UserHandlers,
    private val invites: InviteHandlers,
    private val linkPages: LinkPages,
    private val history: HistoryHandlers,
    private val messenger: SyncMessenger?,
) {
    @Volatile private var lastSweep = 0L

    /**
     * Deletes [band] for everyone: its state first, so every member's next request finds it gone, then the members'
     * band lists, invites, history and attachment files. A small marker tells former members that it was deleted, and
     * by whom. Bucket versioning keeps the objects for 30 days, so a mistake can still be undone by hand
     * (DISASTER_RECOVERY.md).
     */
    fun deleteNow(loaded: Band) {
        val bandId = loaded.id
        // As it is now, not as a request loaded it a moment ago: kept since (or deleted by another request), it stays.
        val band = bands.load(bandId, deleteIfDue = false)?.first?.takeIf { it.deletion.let { d -> d != null && d.deleteAt <= clock() } } ?: return
        val userIds = band.members.mapNotNull { it.userId }
        val by = band.deletion?.let { band.member(it.requestedBy)?.name } ?: band.owner?.name.orEmpty()
        store.put(StorageKeys.deleted(bandId), encode(DeletedBand.serializer(), DeletedBand(band.name, by, clock(), userIds)))
        store.delete(StorageKeys.band(bandId))
        // Gone for everyone from here; each part of the clean-up on its own, so one failing leaves the others done.
        userIds.forEach { runCatching { users.removeBandFromUser(it, bandId) } }
        runCatching { invites.loadCodes(bandId).forEach { code -> runCatching { store.delete(StorageKeys.invite(code)) } } }
        runCatching { store.deleteAll(store.list(StorageKeys.bandPrefix(bandId)).filter { it != StorageKeys.deleted(bandId) }) }
            .onFailure { System.err.println("Could not delete the files of band $bandId: $it") }
        runCatching { linkPages.forget(bandId, band.events.map { it.id }) }
        runCatching { history.deleteBand(bandId) }.onFailure { System.err.println("Could not delete the history of band $bandId: $it") }
        runCatching { note(bandId) }
        // The members' apps let go of it at once (ITERATION_7 §2.3); former members' apps did already.
        runCatching { messenger?.bandChanged(bandId, band.messagedUsers(), null) }
    }

    /**
     * Brings the band's entry in the list of bands waiting up to date with its state, read now: its time when it is to
     * be deleted, none when not (kept, or gone). From the state, so two requests noting in either order agree.
     */
    fun note(bandId: String) = retryOnConflict(exhausted = {}) {
        val deleteAt = bands.load(bandId, deleteIfDue = false)?.first?.deletion?.deleteAt
        val blob = store.get(StorageKeys.SCHEDULED_DELETIONS)
        val waiting = blob?.let { decode(ScheduledDeletions.serializer(), it.content).bands }.orEmpty()
        val updated = if (deleteAt == null) waiting - bandId else waiting + (bandId to deleteAt)
        if (updated == waiting) return@retryOnConflict
        store.put(StorageKeys.SCHEDULED_DELETIONS, encode(ScheduledDeletions.serializer(), ScheduledDeletions(updated)), ifMatch = blob?.etag, ifNoneMatch = blob == null)
    }

    /**
     * Deletes the bands whose time has come, at most once an hour per server instance and a few at a time (the rest
     * on the next request), within one request's time; called by every request.
     */
    fun sweep() {
        val now = clock()
        if (now - lastSweep < SWEEP_EVERY_MS) return
        lastSweep = now
        val waiting = runCatching { store.get(StorageKeys.SCHEDULED_DELETIONS) }.getOrNull()
            ?.let { decode(ScheduledDeletions.serializer(), it.content).bands }.orEmpty()
        val due = waiting.filterValues { it <= now }.keys
        due.take(BANDS_PER_SWEEP).forEach { bandId ->
            runCatching {
                // Loading a band whose time has come deletes it; one kept or moved meanwhile (or gone) is noted as it is.
                bands.load(bandId)
                note(bandId)
            }.onFailure { System.err.println("Could not delete band $bandId: $it") }
        }
        if (due.size > BANDS_PER_SWEEP) lastSweep = 0
    }

    companion object {
        const val SWEEP_EVERY_MS = 60L * 60 * 1000
        const val BANDS_PER_SWEEP = 3
    }
}

/** The bands waiting to be deleted (ITERATION_7 §9), `server/scheduled-deletions.json`: band id to when, epoch millis. */
@Serializable
data class ScheduledDeletions(val bands: Map<String, Long> = emptyMap())
