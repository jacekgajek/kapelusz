package com.jacekgajek.kapelusz.app.bands

import com.jacekgajek.kapelusz.app.storage.KeyValueStore
import com.jacekgajek.kapelusz.app.util.nowMillis
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the bands live on the device (REFACTORING.md 3.4): the key scheme, the backups that are kept before anything
 * is lost, and writing what changed: soon after each change, now when the sync asks, and again later when the device
 * was full. [BandRepository] keeps the bands in memory and tells the store what changed; the store reads the
 * repository's current objects through its [Source] when it writes, so a change made during a write is not taken as
 * saved (I5 A1).
 */
class BandStore(private val store: KeyValueStore, private val scope: CoroutineScope, private val clock: () -> Long = ::nowMillis) {
    /** What is written: the repository's objects as they are now. */
    interface Source {
        val userId: String?
        val index: BandIndex?
        fun local(bandId: Id): LocalBand?
    }

    private var source: Source? = null
    private val dirty = mutableSetOf<Id>()
    private var indexDirty = false
    private val writeLock = Mutex()
    private var retrying = false

    /** The repository the store writes for; once, when it is made. */
    fun bind(source: Source) {
        this.source = source
    }

    suspend fun readIndex(userId: String): String? = store.read(indexKey(userId))

    /** Never lose the only copy (I3 R4): the index as it was before it is replaced. */
    suspend fun backupIndex(userId: String, raw: String) = store.write("${indexKey(userId)}-backup-${clock()}", raw)

    suspend fun readBand(userId: String, bandId: Id): String? = store.read(bandKey(userId, bandId))

    suspend fun hasBand(userId: String, bandId: Id): Boolean = readBand(userId, bandId) != null

    /** Keeps unreadable data as a backup before the user is offered to start over; returns the backup's key. */
    suspend fun backupBand(userId: String, bandId: Id, raw: String): String {
        val key = "${bandKey(userId, bandId)}-backup-${clock()}"
        store.write(key, raw)
        return key
    }

    /** Unsent changes of a band that goes away (access lost, discarded as refused): kept under a key of their own. */
    suspend fun keepUnsent(userId: String, bandId: Id, local: LocalBand) {
        if (local.pending.isEmpty()) return
        store.write("${bandKey(userId, bandId)}-unsent-${clock()}", KapeluszJson.encodeToString(LocalBand.serializer(), local))
    }

    suspend fun remove(userId: String, bandId: Id) {
        dirty -= bandId
        store.remove(bandKey(userId, bandId))
    }

    /** Another user's data is about to be loaded: nothing of the old one is still to be written. */
    fun forgetUnwritten() {
        dirty.clear()
        indexDirty = false
    }

    /** The band changed; [flushSoon]: written in the app scope right away (not for a change found while reading). */
    fun markDirty(bandId: Id, flushSoon: Boolean = true) {
        dirty += bandId
        if (flushSoon) scope.launch { flush() }
    }

    fun markIndexDirty() {
        indexDirty = true
        scope.launch { flush() }
    }

    /**
     * Writes every changed band (and the index) now. Sync calls this before pushing, so a crash right after a push
     * cannot leave an older `nextSeq` on disk and make the next operation reuse a sequence number.
     */
    suspend fun flush(): Boolean = writeLock.withLock {
        val bound = source ?: return@withLock true
        val user = bound.userId ?: return@withLock true
        // A change made while a write is under way (a tap, a sync) is written in another pass rather than forgotten.
        repeat(FLUSH_PASSES) {
            if (!writeChanged(bound, user)) {
                retryFlush()
                return@withLock false
            }
            if (dirty.isEmpty() && !indexDirty) return@withLock true
        }
        retryFlush()
        false
    }

    /**
     * One pass over what changed. Each is unmarked only once written, and only if it didn't change again during its write
     * (I5 A1): otherwise that newer change would count as saved. False when a write failed.
     */
    private suspend fun writeChanged(source: Source, user: String): Boolean {
        var ok = true
        // The index first: it then lists every band that has a file, also after a crash between the writes.
        if (indexDirty) {
            val index = source.index
            if (index == null || write(indexKey(user), KapeluszJson.encodeToString(BandIndex.serializer(), index))) {
                if (source.index === index) indexDirty = false
            } else {
                ok = false
            }
        }
        for (bandId in dirty.toList()) {
            val local = source.local(bandId)
            if (local == null || write(bandKey(user, bandId), KapeluszJson.encodeToString(LocalBand.serializer(), local))) {
                if (source.local(bandId) === local) dirty -= bandId
            } else {
                ok = false
            }
        }
        return ok
    }

    /** False when the device could not store it (e.g. full): it stays marked as unsaved instead of being forgotten. */
    private suspend fun write(key: String, value: String): Boolean = runSuspendCatching { store.write(key, value) }
        .onFailure { println("Could not store $key: $it") }
        .isSuccess

    private fun retryFlush() {
        if (retrying) return
        retrying = true
        scope.launch {
            delay(FLUSH_RETRY_MS)
            retrying = false
            flush()
        }
    }

    companion object {
        /** How soon a write that failed (e.g. a full device) is tried again. */
        const val FLUSH_RETRY_MS = 30_000L

        /** Write passes per flush: changes arriving during writes are rare and few. */
        const val FLUSH_PASSES = 3

        fun indexKey(userId: String) = "band-index-$userId"

        fun bandKey(userId: String, bandId: Id) = "band-$userId-$bandId"
    }
}
