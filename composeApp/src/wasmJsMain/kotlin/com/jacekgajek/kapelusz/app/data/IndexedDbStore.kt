package com.jacekgajek.kapelusz.app.data

import kotlinx.coroutines.await
import kotlin.js.Promise

// The database connection is opened once per page and shared (cached on globalThis).
private fun idbGet(key: String): Promise<JsString?> = js(
    """(globalThis.kapeluszDb ??= new Promise((resolve, reject) => {
        const open = indexedDB.open('kapelusz', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('kv');
        open.onsuccess = () => resolve(open.result);
        open.onerror = () => reject(open.error);
    }).catch((e) => { globalThis.kapeluszDb = undefined; throw e; })).then(db => new Promise((resolve, reject) => {
        const request = db.transaction('kv', 'readonly').objectStore('kv').get(key);
        request.onsuccess = () => resolve(request.result ?? null);
        request.onerror = () => reject(request.error);
    }))""",
)

private fun idbPut(key: String, value: String): Promise<JsAny?> = js(
    """(globalThis.kapeluszDb ??= new Promise((resolve, reject) => {
        const open = indexedDB.open('kapelusz', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('kv');
        open.onsuccess = () => resolve(open.result);
        open.onerror = () => reject(open.error);
    }).catch((e) => { globalThis.kapeluszDb = undefined; throw e; })).then(db => new Promise((resolve, reject) => {
        const transaction = db.transaction('kv', 'readwrite');
        transaction.objectStore('kv').put(value, key);
        transaction.oncomplete = () => resolve(null);
        transaction.onerror = () => reject(transaction.error);
    }))""",
)

private fun idbDelete(key: String): Promise<JsAny?> = js(
    """(globalThis.kapeluszDb ??= new Promise((resolve, reject) => {
        const open = indexedDB.open('kapelusz', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('kv');
        open.onsuccess = () => resolve(open.result);
        open.onerror = () => reject(open.error);
    }).catch((e) => { globalThis.kapeluszDb = undefined; throw e; })).then(db => new Promise((resolve, reject) => {
        const transaction = db.transaction('kv', 'readwrite');
        transaction.objectStore('kv').delete(key);
        transaction.oncomplete = () => resolve(null);
        transaction.onerror = () => reject(transaction.error);
    }))""",
)

/** Browser storage in IndexedDB (database `kapelusz`, object store `kv`). */
class IndexedDbStore : KeyValueStore {
    override suspend fun read(key: String): String? = idbGet(key).await<JsString?>()?.toString()

    override suspend fun write(key: String, value: String) {
        idbPut(key, value).await<JsAny?>()
    }

    override suspend fun remove(key: String) {
        idbDelete(key).await<JsAny?>()
    }
}
