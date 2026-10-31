package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.server.store.HistoryRecord
import com.jacekgajek.kapelusz.server.store.HistoryStore
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The history table as files: a directory per band, a file per record, named by its URL-encoded key. Synchronised, so
 * a band's deletion and a push to it don't cross.
 */
class FileHistoryStore(private val root: Path) : HistoryStore {

    @Synchronized
    override fun newestFirst(bandId: String, from: String, to: String): Sequence<HistoryRecord> {
        val dir = dir(bandId)
        if (!dir.isDirectory()) return emptySequence()
        val keys = dir.listDirectoryEntries().map { it.name }.filter { !it.endsWith(TEMP) }.map { URLDecoder.decode(it, Charsets.UTF_8) }.filter { it in from..to }.sortedDescending()
        // Read now, under the lock: the band may be deleted while the caller goes through them.
        return keys.map { HistoryRecord(it, dir.resolve(fileName(it)).readText()) }.asSequence()
    }

    @Synchronized
    override fun put(bandId: String, records: List<HistoryRecord>) {
        val dir = dir(bandId)
        records.forEach { write(dir.resolve(fileName(it.key)), it.content.toByteArray()) }
    }

    @Synchronized
    override fun deleteBand(bandId: String) {
        val dir = dir(bandId)
        if (dir.isDirectory()) dir.listDirectoryEntries().forEach(Files::delete).also { Files.delete(dir) }
    }

    // Band ids are checked by the API (StorageKeys.safe) before they get here.
    private fun dir(bandId: String) = root.resolve(bandId)

    private fun fileName(key: String) = URLEncoder.encode(key, Charsets.UTF_8)
}
