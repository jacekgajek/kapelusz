package com.jacekgajek.kapelusz.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks for a sync and waits until the band has no changes waiting to be sent (at most [timeoutMs]), e.g. before an
 * invite to a member just added (ITERATION_5 §12). False when they are still waiting (offline).
 */
internal suspend fun AppGraph.awaitPushed(bandId: String, timeoutMs: Long = 15_000): Boolean {
    sync.requestSync()
    return withTimeoutOrNull(timeoutMs) {
        while (bands.local(bandId)?.pending?.isNotEmpty() == true) delay(250)
        true
    } ?: false
}
