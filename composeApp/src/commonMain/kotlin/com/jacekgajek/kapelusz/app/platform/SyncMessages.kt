package com.jacekgajek.kapelusz.app.platform

/**
 * Sync messages from the server through Firebase (ITERATION_7 §2.3), Android only: they say that a band changed, and
 * the app syncs it; nothing is shown. None on the web, which looks for news itself.
 */
interface MessageChannel {
    /** This installation's id, made once and kept (ITERATION_7 §2.4). */
    val installationId: String

    /** Turns messaging on and gives this device's token; null when it can't (no Google Play services, offline). */
    suspend fun token(): String?

    /** Turns messaging off and deletes the token, so nothing more arrives (signing out). */
    suspend fun deleteToken()
}
