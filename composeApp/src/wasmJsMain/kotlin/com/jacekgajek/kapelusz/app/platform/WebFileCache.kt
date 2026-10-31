package com.jacekgajek.kapelusz.app.platform

/** For the session only: the browser has no file cache the app could use for this, and a reload starts over. */
class WebFileCache : ByteCache {
    private val files = LinkedHashMap<String, ByteArray>()

    override suspend fun read(key: String): ByteArray? = files.remove(key)?.also { files[key] = it }

    override suspend fun write(key: String, bytes: ByteArray) {
        files[key] = bytes
        var total = files.values.sumOf { it.size.toLong() }
        val keys = files.keys.iterator()
        while (total > MAX_BYTES && keys.hasNext()) {
            val oldest = keys.next()
            if (oldest == key) continue
            total -= files[oldest]?.size ?: 0
            keys.remove()
        }
    }

    private companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
