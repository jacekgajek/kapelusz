package com.jacekgajek.artsplit.app.data

import kotlinx.coroutines.await
import kotlin.js.Promise

private fun idbGet(key: String): Promise<JsString?> = js(
    """new Promise((resolve, reject) => {
        const open = indexedDB.open('art-split', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('kv');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const request = open.result.transaction('kv', 'readonly').objectStore('kv').get(key);
            request.onsuccess = () => resolve(request.result ?? null);
            request.onerror = () => reject(request.error);
        };
    })""",
)

private fun idbPut(key: String, value: String): Promise<JsAny?> = js(
    """new Promise((resolve, reject) => {
        const open = indexedDB.open('art-split', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('kv');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const transaction = open.result.transaction('kv', 'readwrite');
            transaction.objectStore('kv').put(value, key);
            transaction.oncomplete = () => resolve(null);
            transaction.onerror = () => reject(transaction.error);
        };
    })""",
)

/** Browser storage in IndexedDB (database `art-split`, object store `kv`). */
class IndexedDbStore : KeyValueStore {
    override suspend fun read(key: String): String? = idbGet(key).await<JsString?>()?.toString()

    override suspend fun write(key: String, value: String) {
        idbPut(key, value).await<JsAny?>()
    }
}
