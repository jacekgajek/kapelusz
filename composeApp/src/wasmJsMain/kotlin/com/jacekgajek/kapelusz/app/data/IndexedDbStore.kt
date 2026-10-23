package com.jacekgajek.kapelusz.app.data

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * One operation ([op]: `get`, `put` or `delete`) in its own transaction. The connection is opened once per page and
 * shared (cached on globalThis), and forgotten when the browser closes it (iPhone Safari does after the app was in the
 * background, or when site data is cleared): the operation then reconnects once instead of failing until a reload. A
 * transaction the browser aborts (e.g. storage full, reported only as an abort) fails instead of never finishing
 * (V5-M1 D4).
 */
private fun idb(op: String, key: String, value: String?): Promise<JsAny?> = js(
    """(() => {
        const open = () => (globalThis.kapeluszDb ??= new Promise((resolve, reject) => {
            const request = indexedDB.open('kapelusz', 1);
            request.onupgradeneeded = () => request.result.createObjectStore('kv');
            request.onsuccess = () => {
                const db = request.result;
                const forget = () => { globalThis.kapeluszDb = undefined; };
                db.onclose = forget;
                db.onversionchange = () => { db.close(); forget(); };
                resolve(db);
            };
            request.onerror = () => reject(request.error);
        }).catch((e) => { globalThis.kapeluszDb = undefined; throw e; }));
        const run = (db) => new Promise((resolve, reject) => {
            const transaction = db.transaction('kv', op === 'get' ? 'readonly' : 'readwrite');
            const store = transaction.objectStore('kv');
            const request = op === 'get' ? store.get(key) : op === 'put' ? store.put(value, key) : store.delete(key);
            transaction.oncomplete = () => resolve(op === 'get' ? (request.result ?? null) : null);
            transaction.onerror = () => reject(transaction.error);
            transaction.onabort = () => reject(transaction.error ?? new Error('IndexedDB transaction aborted'));
        });
        return open().then(run).catch((e) => {
            if (e?.name !== 'InvalidStateError') throw e;
            globalThis.kapeluszDb = undefined;
            return open().then(run);
        });
    })()""",
)

/** Browser storage in IndexedDB (database `kapelusz`, object store `kv`). */
class IndexedDbStore : KeyValueStore {
    override suspend fun read(key: String): String? = idb("get", key, null).await<JsAny?>()?.let { (it as JsString).toString() }

    override suspend fun write(key: String, value: String) {
        idb("put", key, value).await<JsAny?>()
    }

    override suspend fun remove(key: String) {
        idb("delete", key, null).await<JsAny?>()
    }
}
