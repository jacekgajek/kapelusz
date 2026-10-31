package com.jacekgajek.kapelusz.server.store

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts calls to a store and their time, for the per-request timing log. One Lambda instance serves one request at a
 * time, so [reset] at the start of each request is enough; calls made in parallel add up their times.
 */
class CallTimer(private val label: String) {
    private val calls = AtomicInteger()
    private val nanos = AtomicLong()

    fun reset() {
        calls.set(0)
        nanos.set(0)
    }

    /** E.g. `s3=15 calls/1290ms`. */
    fun summary(): String = "$label=${calls.get()} calls/${nanos.get() / 1_000_000}ms"

    fun <T> timed(call: () -> T): T {
        val start = System.nanoTime()
        try {
            return call()
        } finally {
            calls.incrementAndGet()
            nanos.addAndGet(System.nanoTime() - start)
        }
    }
}

/** [store] with its calls counted by [timer]. */
class TimedBlobStore(private val store: BlobStore, private val timer: CallTimer) : BlobStore by store {
    override fun get(key: String): Blob? = timer.timed { store.get(key) }

    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String = timer.timed { store.put(key, content, ifMatch, ifNoneMatch) }

    override fun delete(key: String) = timer.timed { store.delete(key) }

    override fun deleteAll(keys: List<String>) = timer.timed { store.deleteAll(keys) }

    override fun list(prefix: String): List<String> = timer.timed { store.list(prefix) }

    override fun exists(key: String): Boolean = timer.timed { store.exists(key) }
}
