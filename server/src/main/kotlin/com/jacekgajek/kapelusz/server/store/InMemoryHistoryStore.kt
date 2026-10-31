package com.jacekgajek.kapelusz.server.store

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/** [HistoryStore] in memory: for tests, and the local server's `--memory`. */
class InMemoryHistoryStore : HistoryStore {
    val bands = ConcurrentHashMap<String, ConcurrentSkipListMap<String, String>>()

    override fun newestFirst(bandId: String, from: String, to: String): Sequence<HistoryRecord> = bands[bandId]?.subMap(from, true, to, true)?.descendingMap()?.map { (key, content) -> HistoryRecord(key, content) }?.asSequence().orEmpty()

    override fun put(bandId: String, records: List<HistoryRecord>) {
        val band = bands.computeIfAbsent(bandId) { ConcurrentSkipListMap() }
        records.forEach { band[it.key] = it.content }
    }

    override fun deleteBand(bandId: String) {
        bands.remove(bandId)
    }
}
