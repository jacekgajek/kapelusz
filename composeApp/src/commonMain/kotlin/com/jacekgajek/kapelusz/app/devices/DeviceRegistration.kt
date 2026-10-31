package com.jacekgajek.kapelusz.app.devices

import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.bands.BandIndex
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import com.jacekgajek.kapelusz.app.sync.SyncEvent
import com.jacekgajek.kapelusz.app.sync.SyncState
import com.jacekgajek.kapelusz.app.util.nowMillis
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The server's list of devices to send sync messages to. */
interface DevicesApi {
    /** This installation's Firebase token; the answer says whether sync messages will come. */
    suspend fun registerDevice(installationId: String, token: String): Boolean

    /** No more sync messages for this installation (signing out). */
    suspend fun removeDevice(installationId: String)
}

/**
 * This device on the server's list for sync messages; without a [channel] (the web), nothing. A
 * reaction, nothing calls it to register: it does so whenever what the server keeps the device for changes — the user
 * signed in (once their bands are loaded), whether they are in a band, a band of theirs the server now has
 * ([SyncEvent.BandCreated]), a new token ([tokenChanged]) — and when the app comes to the screen after a registration
 * that failed (offline), or a day after the last one, as the server forgets devices unseen for 60 days. The server's
 * answer sets how often the app looks for news itself ([messaged]); any failure leaves it every 30 s.
 */
class DeviceRegistration(
    private val channel: MessageChannel?,
    private val session: StateFlow<Session?>,
    index: StateFlow<BandIndex?>,
    private val api: DevicesApi,
    syncEvents: Flow<SyncEvent>,
    syncState: StateFlow<SyncState>,
    private val messaged: (Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::nowMillis,
) {
    private val nudges = MutableStateFlow(0)
    private var registration: Job? = null
    private var failed = false
    private var lastAt = 0L

    init {
        if (channel != null) {
            scope.launch {
                // Only once the user's bands are loaded (the index): signing in loads them first. Told apart by user
                // before dropping the signed-out stretches: signing in again, even as the same user, registers again.
                combine(session, index, nudges) { s, i, nudge -> Triple(s?.userId, i?.bands?.isNotEmpty(), nudge) }
                    .distinctUntilChanged()
                    .filter { (userId, hasBands, _) -> userId != null && hasBands != null }
                    .collect { register(channel) }
            }
            // Subscribed before anything can emit: a band created with nobody listening would be missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                syncEvents.collect { if (it is SyncEvent.BandCreated) nudge() }
            }
            scope.launch {
                var before: SyncState = syncState.value
                syncState.collect { state ->
                    if (before == SyncState.Stopped && state != SyncState.Stopped) onScreen()
                    before = state
                }
            }
        }
    }

    /** Firebase gave this device a new token. */
    fun tokenChanged() = nudge()

    /** The app came to the screen (the sync loop started): a failed registration is tried again, a day-old one renewed. */
    private fun onScreen() {
        if (registration?.isActive == true) return
        if (failed || clock() - lastAt >= KEEP_ALIVE_MS) nudge()
    }

    private fun nudge() = nudges.update { it + 1 }

    private fun register(channel: MessageChannel) {
        // The latest one counts: an older one finishing later must not overwrite its answer.
        registration?.cancel()
        registration = scope.launch {
            val result = runSuspendCatching { channel.token()?.let { api.registerDevice(channel.installationId, it) } ?: false }
            if (!isActive) return@launch
            failed = result.isFailure
            lastAt = clock()
            messaged(result.getOrDefault(false))
        }
    }

    /** On signing out, before the session goes: no more messages to this device. [tellServer]: see [Account.signOut]. */
    suspend fun unregister(tellServer: Boolean) {
        registration?.cancel()
        failed = false
        messaged(false)
        val channel = channel ?: return
        val usable = session.value?.let { it.apiToken != null && it.apiExpiresAt > clock() + 60_000 } == true
        if (tellServer && usable) runSuspendCatching { withTimeout(5_000) { api.removeDevice(channel.installationId) } }
        // Before the session goes: a quick sign-in again must not have its new token deleted by this one.
        runSuspendCatching { withTimeout(5_000) { channel.deleteToken() } }
    }

    private companion object {
        /** Registered again this long after the last time, when the app comes to the screen: seen, so not forgotten. */
        const val KEEP_ALIVE_MS = 24 * 60 * 60_000L
    }
}
