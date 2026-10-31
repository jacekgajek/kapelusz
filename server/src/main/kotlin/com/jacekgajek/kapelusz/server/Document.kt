package com.jacekgajek.kapelusz.server

import kotlinx.serialization.KSerializer

/**
 * A JSON object at one key with a known type (REFACTORING.md 5.2): the read–modify–conditional-write dance, once,
 * instead of in every handler. [update] is the usual case; [read] and [write] are for the few places that must do
 * something else between the read and the write.
 */
internal class Document<T>(private val store: BlobStore, val key: String, private val serializer: KSerializer<T>) {
    /** The value and its ETag; null when nothing is stored. */
    fun read(): Pair<T, String>? = store.get(key)?.let { decode(serializer, it.content) to it.etag }

    fun get(): T? = read()?.first

    /**
     * Writes [value] over what was read with [etag] (null: nothing was, and nothing must have been written meanwhile);
     * throws [PreconditionFailed] otherwise. Returns the new ETag.
     */
    fun write(value: T, etag: String?): String = store.put(key, encode(serializer, value), ifMatch = etag, ifNoneMatch = etag == null)

    /** Writes [value] only if nothing is stored yet; throws [PreconditionFailed] otherwise. */
    fun create(value: T): String = store.put(key, encode(serializer, value), ifNoneMatch = true)

    fun delete() = store.delete(key)

    /**
     * Reads, has [change] make the next value from what is stored (null: nothing is), and writes it unless something
     * changed meanwhile, in which case all of it again, at most [Api.MAX_ATTEMPTS] times, then [exhausted]. The same
     * value back writes nothing; null back leaves what is stored (so a missing object stays missing). Returns what
     * [change] returned the last time.
     */
    fun update(exhausted: () -> T? = { throw busy("Could not update $key") }, change: (T?) -> T?): T? = retryOnConflict(exhausted) {
        val stored = read()
        val next = change(stored?.first)
        if (next != null && next != stored?.first) write(next, stored?.second)
        next
    }
}

// The server's documents, each at its key (see StorageKeys).

internal fun BlobStore.userRecord(userId: String) = Document(this, StorageKeys.user(userId), UserRecord.serializer())

internal fun BlobStore.joinAttempts(userId: String) = Document(this, StorageKeys.joinAttempts(userId), JoinAttempts.serializer())

internal fun BlobStore.devices(userId: String) = Document(this, StorageKeys.devices(userId), StoredDevices.serializer())

internal fun BlobStore.inviteCodes(bandId: String) = Document(this, StorageKeys.bandInvites(bandId), InviteCodes.serializer())

internal fun BlobStore.scheduledDeletions() = Document(this, StorageKeys.SCHEDULED_DELETIONS, ScheduledDeletions.serializer())
