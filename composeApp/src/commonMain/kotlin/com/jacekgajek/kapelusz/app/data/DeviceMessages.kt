package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** This device on the server's list for sync messages (ITERATION_7 §2.4); without a [channel] (the web), nothing. */
class DeviceMessages(
    private val channel: MessageChannel?,
    private val sessions: SessionRepository,
    private val api: ApiClient,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
) {
    private var registration: Job? = null

    /**
     * Registers this device, while signed in: after loading, signing in, joining a band, a new Firebase token, and each
     * time the Android app comes to the screen (a first band may have been made since). The server's answer sets how
     * often the app looks for news itself; any failure leaves it every 30 s.
     */
    fun register() {
        val channel = channel ?: return
        if (sessions.session.value == null) return
        // The latest one counts: an older one finishing later must not overwrite its answer.
        registration?.cancel()
        registration = scope.launch {
            val messaged = runSuspendCatching { channel.token()?.let { api.registerDevice(channel.installationId, it) } ?: false }.getOrDefault(false)
            if (isActive) sync.messaged = messaged
        }
    }

    /** On signing out, before the session goes: no more messages to this device. [tellServer]: see [Account.signOut]. */
    suspend fun unregister(tellServer: Boolean) {
        registration?.cancel()
        sync.messaged = false
        val channel = channel ?: return
        val usable = sessions.session.value?.let { it.apiToken != null && it.apiExpiresAt > nowMillis() + 60_000 } == true
        if (tellServer && usable) runSuspendCatching { withTimeout(5_000) { api.removeDevice(channel.installationId) } }
        // Before the session goes: a quick sign-in again must not have its new token deleted by this one.
        runSuspendCatching { withTimeout(5_000) { channel.deleteToken() } }
    }
}
