package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a page of the server's history leniently (ITERATION_3 R12): the page and each entry are decoded with the
 * shape of [HistoryEntry], but operations and changes stay JSON until read one by one. So a change made by a newer
 * app version (an operation this one does not know) shows as such instead of failing the whole page, and a broken
 * entry is skipped.
 */
object HistoryDecoder {
    /** [com.jacekgajek.kapelusz.api.HistoryResponse], with the entries left as JSON. */
    @Serializable
    private class Page(val month: String? = null, val entries: List<JsonElement> = emptyList(), val next: String? = null)

    /** [HistoryEntry], with its operations and changes left as JSON. */
    @Serializable
    private class Entry(
        val userId: String,
        val memberId: String? = null,
        val at: Long,
        val ops: List<JsonElement> = emptyList(),
        val changes: Map<String, JsonElement> = emptyMap(),
        val id: String? = null,
    )

    /** A page as the server sent it; [month] when the page does not name its own. */
    fun page(json: JsonElement, month: String): HistoryPage {
        val page = decodeOrNull(Page.serializer(), json) ?: Page()
        return HistoryPage(page.month ?: month, page.entries.mapNotNull(::item), page.next)
    }

    /** One entry; null when even its header (who and when) cannot be read. */
    fun item(json: JsonElement): HistoryItem? {
        val entry = decodeOrNull(Entry.serializer(), json) ?: return null
        // Each operation on its own, in order: one this version cannot read becomes a placeholder.
        val ops = entry.ops.mapIndexed { index, op ->
            decodeOrNull(OpEnvelope.serializer(), op)?.let { ReadOp.Known(it) } ?: ReadOp.Unknown(unreadable(op, "${entry.at}-$index"))
        }
        val header = HistoryEntry(
            userId = entry.userId,
            memberId = entry.memberId,
            at = entry.at,
            ops = ops.filterIsInstance<ReadOp.Known>().map { it.envelope },
            // What each operation changed, for undo; one this version cannot read just cannot be undone here.
            changes = entry.changes.mapNotNull { (opId, change) -> decodeOrNull(BandChange.serializer(), change)?.let { opId to it } }.toMap(),
            id = entry.id,
        )
        return HistoryItem(header, ops)
    }

    /** What can still be told about an operation this version cannot read: its id and its event, if any. */
    private fun unreadable(op: JsonElement, fallbackId: String): UnreadableOp {
        val raw = op as? JsonObject
        return UnreadableOp(
            opId = raw?.get("opId")?.stringOrNull() ?: fallbackId,
            eventId = (raw?.get("op") as? JsonObject)?.get("eventId")?.stringOrNull(),
        )
    }

    private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun <T> decodeOrNull(serializer: KSerializer<T>, json: JsonElement): T? = runCatching { KapeluszJson.decodeFromJsonElement(serializer, json) }.getOrNull()
}
