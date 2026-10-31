package com.jacekgajek.kapelusz.server.links

import com.jacekgajek.kapelusz.server.bands.BandAccess
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import com.jacekgajek.kapelusz.server.store.StorageKeys
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable

/** Which band an event is in, `events/{eventId}.json`: link cards find an event without reading every band. */
@Serializable
data class EventPointer(val bandId: String)

/** The pointers from events to their bands (I6 §2), kept as pushes create and delete events; read by [LinkPages]. */
internal class EventPointers(private val store: BlobStore, private val bands: BandAccess) {
    /** The band [eventId] is in, as far as the pointers say. */
    fun bandOf(eventId: String): String? {
        val stored = store.get(StorageKeys.eventPointer(eventId)) ?: return null
        return decode(EventPointer.serializer(), stored.content).bandId
    }

    /**
     * Records where events a push created are, so their links find them; in parallel, as a push can create hundreds.
     * The first band to have an event id keeps it: another band giving an event the same id (anyone who saw the link
     * could) must not take over its card. Unless that band no longer has the event.
     */
    fun pointTo(bandId: String, eventIds: Collection<String>) = inParallel(eventIds) { eventId ->
        val key = StorageKeys.eventPointer(eventId)
        val content = encode(EventPointer.serializer(), EventPointer(bandId))
        try {
            store.put(key, content, ifNoneMatch = true)
        } catch (ignored: PreconditionFailed) {
            val existing = store.get(key) ?: return@inParallel
            val owner = decode(EventPointer.serializer(), existing.content).bandId
            if (owner != bandId && bands.load(owner, deleteIfDue = false)?.first?.event(eventId) == null) {
                runCatching { store.put(key, content, ifMatch = existing.etag) }
            }
        }
    }

    /** Takes away the pointers of [eventIds] (deleted, or their band deleted) where they still point to [bandId]. */
    fun forget(bandId: String, eventIds: Collection<String>) = inParallel(eventIds) { eventId ->
        if (bandOf(eventId) == bandId) store.delete(StorageKeys.eventPointer(eventId))
    }

    private fun inParallel(eventIds: Collection<String>, write: (String) -> Unit) {
        if (eventIds.isEmpty()) return
        eventIds.map { eventId ->
            writers.submit {
                runCatching { write(eventId) }.onFailure { System.err.println("Could not update the pointer of event $eventId: $it") }
            }
        }.forEach { it.get() }
    }

    private companion object {
        val writers by lazy { Executors.newFixedThreadPool(8) { task -> Thread(task, "event-pointers").apply { isDaemon = true } } }
    }
}
