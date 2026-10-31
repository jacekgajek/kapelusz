package com.jacekgajek.kapelusz.server.store

/** One operation of a push in a band's history: [content] is a one-operation `HistoryEntry` in JSON. */
data class HistoryRecord(val key: String, val content: String)

/**
 * A band's history, one record per operation (V5 history speed): [HistoryRecord.key] is the push's entry name and the
 * operation's place in it, `yyyy-MM/<time>-<uuid>.json#007`, so a band's records sort by time and a push's stay
 * together, in order.
 */
interface HistoryStore {
    /** Records of [bandId] with keys from [from] to [to] (both inclusive), newest first, read as they are used. */
    fun newestFirst(bandId: String, from: String, to: String): Sequence<HistoryRecord>

    /** Writes [records], all of one push. */
    fun put(bandId: String, records: List<HistoryRecord>)

    fun deleteBand(bandId: String)
}
