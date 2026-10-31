package com.jacekgajek.kapelusz.app.storage

/** [KeyValueStore] in memory, for tests. */
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
