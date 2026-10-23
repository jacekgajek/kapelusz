package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.HistoryResponse
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.BandPart
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.Undo
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** A band's history: one file per accepted push, per month; pages of it, and the undos that name an entry. */
internal class HistoryHandlers(
    private val store: BlobStore,
    private val clock: () -> Long,
    private val bands: BandAccess,
) {
    /**
     * One page of a month's history, newest first. [before] is the `next` cursor of the previous page; listing keys is
     * cheap, only the page's entries are fetched, so a busy month never times out.
     */
    fun history(user: VerifiedUser, bandId: String, month: String?, before: String?): HttpResponse {
        bands.loadForMember(user, bandId)
        val selected = month ?: monthOf(clock())
        badRequestIf(!selected.matches(Regex("\\d{4}-\\d{2}"))) { "month must be yyyy-MM" }
        val prefix = StorageKeys.logMonth(bandId, selected)
        val keys = store.list(prefix).asReversed().let { all -> if (before == null) all else all.dropWhile { it >= prefix + before } }
        // At most HISTORY_PAGE entries and about HISTORY_PAGE_BYTES (the recorded changes can be large), at least one.
        val page = mutableListOf<String>()
        val entries = mutableListOf<HistoryEntry>()
        var bytes = 0
        // Read in parallel batches (one at a time, a page of a hundred small files took seconds), stopping at the budget.
        var full = false
        for (batch in keys.take(Api.HISTORY_PAGE).chunked(LOG_READ_BATCH)) {
            for ((key, blob) in batch.zip(readAll(batch))) {
                blob ?: continue
                val size = blob.content.utf8Size()
                if (entries.isNotEmpty() && bytes + size > Api.HISTORY_PAGE_BYTES) {
                    full = true
                    break
                }
                bytes += size
                page += key
                // Its name, so an undo can say which entry it means (ITERATION_2 §7.2).
                // An unreadable entry is skipped, not the whole page: the rest of the history stays readable.
                val entry = runCatching { decode(HistoryEntry.serializer(), blob.content) }.getOrNull() ?: continue
                entries += entry.copy(id = key.removePrefix(StorageKeys.log(bandId)))
            }
            if (full) break
        }
        val next = if (keys.size > page.size && page.isNotEmpty()) page.last().removePrefix(prefix) else null
        return ok(HistoryResponse.serializer(), HistoryResponse(selected, entries, next))
    }

    /** The files at [keys] (null: gone), read at the same time; if one fails, the others are not waited for. */
    private fun readAll(keys: List<String>): List<Blob?> {
        val reads = keys.map { key -> logReader.submit<Blob?> { store.get(key) } }
        return try {
            reads.map { it.get() }
        } catch (e: ExecutionException) {
            reads.forEach { it.cancel(true) }
            throw e.cause ?: e
        }
    }

    /**
     * [op] with an undo's original operation and change replaced by the ones in the history entry it names; an undo of
     * something not there (or from before changes were recorded) gets an empty change, which the reducer refuses.
     * A storage failure is not a refusal: it fails the push, and the app tries again.
     */
    fun recordedUndo(bandId: String, op: Operation): Operation {
        if (op !is Undo) return op
        val refused = op.copy(change = BandChange(BandPart(), BandPart()))
        val name = op.entry?.takeIf { it.matches(Regex("\\d{4}-\\d{2}/[0-9]+-[0-9a-f-]{36}\\.json")) } ?: return refused
        // An unreadable entry refuses only this undo; failing the push would make the app retry it forever.
        val entry = store.get(StorageKeys.logEntry(bandId, name))?.let { runCatching { decode(HistoryEntry.serializer(), it.content) }.getOrNull() } ?: return refused
        // Op ids are unique within a push, so this is the one operation, with its own change.
        val original = entry.ops.singleOrNull { it.opId == op.undoneOpId }?.op ?: return refused
        val change = entry.changes[op.undoneOpId] ?: return refused
        return op.copy(original = original, change = change)
    }

    fun append(user: VerifiedUser, band: Band, bandId: String, ops: List<OpEnvelope>, changes: Map<String, BandChange>) {
        val now = clock()
        val recorded = changes.filterKeys { id -> ops.any { it.opId == id } }
        // A huge offline batch is logged without its changes (it cannot be undone), so history pages stay small.
        val kept = recorded.takeIf { encode(MapSerializer(String.serializer(), BandChange.serializer()), it).length <= MAX_LOGGED_CHANGES } ?: emptyMap()
        val entry = HistoryEntry(user.userId, band.memberOfUser(user.userId)?.id, now, ops, kept)
        store.put(StorageKeys.logEntry(bandId, "${monthOf(now)}/$now-${UUID.randomUUID()}.json"), encode(HistoryEntry.serializer(), entry), ifNoneMatch = true)
    }

    companion object {
        private const val LOG_READ_BATCH = 16

        /** Reads history files in parallel (network-bound, so more threads than cores). */
        private val logReader by lazy { Executors.newFixedThreadPool(16) { task -> Thread(task, "log-reader").apply { isDaemon = true } } }

        /** Above this, a push's changes are not logged (ITERATION_2 §7.2): the entry lists its operations only. */
        const val MAX_LOGGED_CHANGES = 1_000_000
    }
}
