package com.jacekgajek.splitart.app

import kotlinx.coroutines.await
import kotlin.js.Promise

// Web Locks API: the page holding "split-art-active-tab" is the only one using the stored data. Two tabs would share
// one device id and overwrite each other's saved bands, silently losing changes.
private fun requestTabLock(steal: Boolean, onLost: () -> Unit): Promise<JsBoolean> = js(
    """(!navigator.locks) ? Promise.resolve(true) : new Promise((resolve) => {
        navigator.locks.request('split-art-active-tab', steal ? { steal: true } : { ifAvailable: true }, (lock) => {
            if (!lock) { resolve(false); return; }
            resolve(true);
            return new Promise(() => {});
        }).catch(() => onLost());
    })""",
)

private fun reloadPage(): Unit = js("{ window.location.reload(); }")

object TabLock {
    /**
     * Takes the lock; with [steal] even from another tab. Returns false if another tab has it. When another tab later
     * steals it, this page reloads and then shows the "open in another tab" notice.
     */
    suspend fun acquire(steal: Boolean): Boolean = requestTabLock(steal) { reloadPage() }.await<JsBoolean>().toBoolean()
}
