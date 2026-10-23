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
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * A band's history: an entry per accepted push, stored as a record per operation ([HistoryStore]); pages of it, and the
 * undos that name an entry.
 */
internal class HistoryHandlers(
    private val records: HistoryStore,
    private val clock: () -> Long,
    private val bands: BandAccess,
) {
    /**
     * One page of a month's history, newest first. [before] is the `next` cursor of the previous page.
     *
     * With [months] above 1, the first page of that many months back from [month] in one answer (no `next`): one
     * request instead of one per month, so the app does not wake a new server instance for each month.
     */
    fun history(user: VerifiedUser, bandId: String, month: String?, before: String?, months: String? = null): HttpResponse {
        val selected = month ?: monthOf(clock())
        badRequestIf(!selected.matches(Regex("\\d{4}-\\d{2}"))) { "month must be yyyy-MM" }
        val first = runCatching { YearMonth.parse(selected) }.getOrNull()
        badRequestIf(first == null) { "month must be yyyy-MM" }
        val count = if (months == null) 1 else months.toIntOrNull() ?: 0
        badRequestIf(count !in 1..MAX_MONTHS) { "months must be 1 to $MAX_MONTHS" }
        badRequestIf(count > 1 && before != null) { "before pages a single month" }
        // Each month gets at most HISTORY_PAGE entries and its share of about HISTORY_PAGE_BYTES (the recorded changes
        // can be large): a busy month never crowds out the others, and the answer stays small.
        val budget = Api.HISTORY_PAGE_BYTES / count
        // The months are read while the membership is checked; nothing is answered unless the check passes.
        val pages = (0 until count).map { offset ->
            reader.submit<Page> { page(bandId, first!!.minusMonths(offset.toLong()).toString(), before.takeIf { offset == 0 }, budget) }
        }
        try {
            bands.loadForMember(user, bandId)
        } catch (e: Exception) {
            pages.forEach { it.cancel(true) }
            throw e
        }
        val read = awaitAll(pages)
        val next = read.first().next.takeIf { count == 1 }
        return ok(HistoryResponse.serializer(), HistoryResponse(selected, read.flatMap { it.entries }, next))
    }

    private class Page(val entries: List<HistoryEntry>, val next: String?)

    /** Up to [Api.HISTORY_PAGE] entries of [month] older than [before], about [budget] bytes, at least one. */
    private fun page(bandId: String, month: String, before: String?, budget: Int): Page {
        val prefix = "$month/"
        // A push's records are "<entry>#<index>", all after "<entry>" itself: `before` leaves out the whole push.
        val newest = if (before == null) "$prefix￿" else prefix + before
        val entries = mutableListOf<HistoryEntry>()
        var bytes = 0
        var last: String? = null
        for (push in pushes(records.newestFirst(bandId, prefix, newest))) {
            if (entries.size == Api.HISTORY_PAGE || (last != null && bytes + push.bytes > budget)) return Page(entries, last!!.removePrefix(prefix))
            bytes += push.bytes
            last = push.name
            push.entry?.let { entries += it }
        }
        return Page(entries, null)
    }

    /** One push: its entry name, its operations together (null: none readable), and their stored size. */
    private class Push(val name: String, val entry: HistoryEntry?, val bytes: Int)

    /** [newestFirst] records grouped back into pushes, read as they are used. */
    private fun pushes(newestFirst: Sequence<HistoryRecord>): Sequence<Push> = sequence {
        var group = mutableListOf<HistoryRecord>()
        for (record in newestFirst) {
            if (group.isNotEmpty() && entryName(group.first().key) != entryName(record.key)) {
                yield(push(group))
                group = mutableListOf()
            }
            group += record
        }
        if (group.isNotEmpty()) yield(push(group))
    }

    /** The push of [records] (newest first, so its operations last to first) as one entry, named so an undo can say which. */
    private fun push(records: List<HistoryRecord>): Push {
        val name = entryName(records.first().key)
        // An unreadable record leaves out only its operation: the rest of the history stays readable.
        val parts = records.asReversed().mapNotNull { runCatching { decode(HistoryEntry.serializer(), it.content) }.getOrNull() }
        val entry = parts.firstOrNull()?.copy(
            ops = parts.flatMap { it.ops },
            changes = parts.fold(emptyMap()) { all, part -> all + part.changes },
            id = name,
        )
        return Push(name, entry, records.sumOf { it.content.utf8Size() })
    }

    private fun entryName(key: String) = key.substringBefore('#')

    /**
     * [op] with an undo's original operation and change replaced by the ones in the history entry it names; an undo of
     * something not there (or from before changes were recorded) gets an empty change, which the reducer refuses.
     * A storage failure is not a refusal: it fails the push, and the app tries again.
     */
    fun recordedUndo(bandId: String, op: Operation): Operation {
        if (op !is Undo) return op
        val refused = op.copy(change = BandChange(BandPart(), BandPart()))
        val name = op.entry?.takeIf { it.matches(Regex("\\d{4}-\\d{2}/[0-9]+-[0-9a-f-]{36}\\.json")) } ?: return refused
        // An unreadable record refuses only this undo; failing the push would make the app retry it forever.
        val parts = records.newestFirst(bandId, "$name#", "$name#￿")
            .mapNotNull { runCatching { decode(HistoryEntry.serializer(), it.content) }.getOrNull() }
            .filter { part -> part.ops.any { it.opId == op.undoneOpId } }
            .toList()
        // Op ids are unique within a push, so this is the one operation, with its own change.
        val part = parts.singleOrNull() ?: return refused
        val original = part.ops.singleOrNull { it.opId == op.undoneOpId }?.op ?: return refused
        val change = part.changes[op.undoneOpId] ?: return refused
        return op.copy(original = original, change = change)
    }

    fun append(user: VerifiedUser, band: Band, bandId: String, ops: List<OpEnvelope>, changes: Map<String, BandChange>) {
        val now = clock()
        val recorded = changes.filterKeys { id -> ops.any { it.opId == id } }
        // A huge offline batch is logged without its changes (it cannot be undone), so history pages stay small.
        val kept = recorded.takeIf { encode(MapSerializer(String.serializer(), BandChange.serializer()), it).utf8Size() <= MAX_LOGGED_CHANGES } ?: emptyMap()
        val memberId = band.memberOfUser(user.userId)?.id
        val name = "${monthOf(now)}/$now-${UUID.randomUUID()}.json"
        val pushed = ops.mapIndexedNotNull { index, envelope ->
            val part = HistoryEntry(user.userId, memberId, now, listOf(envelope), kept[envelope.opId]?.let { mapOf(envelope.opId to it) } ?: emptyMap())
            // A record over what the table takes keeps its operation without the change (that one cannot be undone);
            // an operation too large even alone is left out, rather than the whole push failing to be written.
            val content = encode(HistoryEntry.serializer(), part).takeIf { it.utf8Size() <= MAX_RECORD_BYTES }
                ?: encode(HistoryEntry.serializer(), part.copy(changes = emptyMap())).takeIf { it.utf8Size() <= MAX_RECORD_BYTES }
                ?: return@mapIndexedNotNull null.also { System.err.println("History of band $bandId: operation ${envelope.opId} too large to record") }
            HistoryRecord("$name#${index.toString().padStart(3, '0')}", content)
        }
        records.put(bandId, pushed)
    }

    fun deleteBand(bandId: String) = records.deleteBand(bandId)

    /** The results of [running]; if one fails, the others are not waited for. */
    private fun <T> awaitAll(running: List<Future<T>>): List<T> = try {
        running.map { it.get() }
    } catch (e: ExecutionException) {
        running.forEach { it.cancel(true) }
        throw e.cause ?: e
    }

    companion object {
        /** Months one history request may cover. */
        const val MAX_MONTHS = 3

        /** Reads the months of one request in parallel. */
        private val reader by lazy { Executors.newFixedThreadPool(MAX_MONTHS) { task -> Thread(task, "history-reader").apply { isDaemon = true } } }

        /** Above this, a push's changes are not logged (ITERATION_2 §7.2): the entry lists its operations only. */
        const val MAX_LOGGED_CHANGES = 1_000_000

        /** A DynamoDB item is at most 400 KB, keys included. */
        const val MAX_RECORD_BYTES = 350_000
    }
}
