package com.jacekgajek.kapelusz.app.storage

/** Small persistent string store: app-private files on Android, IndexedDB on the web. */
interface KeyValueStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}
