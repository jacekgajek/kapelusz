package com.jacekgajek.kapelusz.app.storage

/** Small persistent string store: app-private files on Android, IndexedDB on the web. */
interface KeyValueStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

class InMemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values = initial.toMutableMap()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
