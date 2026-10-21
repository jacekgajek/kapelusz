package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.app.auth.GoogleAuth
import com.jacekgajek.artsplit.app.auth.SessionRepository
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.Rejection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide objects, created once per app start (no DI framework). */
class AppGraph(store: KeyValueStore, google: GoogleAuth) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val bands = BandRepository(store, scope)
    val settings = SettingsRepository(store, scope)
    val sessions = SessionRepository(store, google, ::nowMillis)

    private var loaded = false

    /** Loads persisted state once per process; later calls (e.g. after Android activity recreation) are no-ops. */
    suspend fun load() {
        if (loaded) return
        settings.load()
        sessions.load()
        bands.load()
        loaded = true
    }

    /** Applies an operation and remembers the event it touched for the "Continue" card. */
    fun dispatch(op: Operation): Rejection? {
        val rejection = bands.dispatch(op)
        if (rejection == null) op.eventId?.let { eventId -> settings.update { it.copy(lastEditedEventId = eventId) } }
        return rejection
    }
}
