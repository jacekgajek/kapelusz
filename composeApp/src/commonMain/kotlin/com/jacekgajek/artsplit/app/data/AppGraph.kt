package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.auth.GoogleAuth
import com.jacekgajek.artsplit.app.auth.SessionRepository
import com.jacekgajek.artsplit.app.sync.ApiClient
import com.jacekgajek.artsplit.app.sync.SyncEngine
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.Rejection
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide objects, created once per app start (no DI framework). */
class AppGraph(store: KeyValueStore, google: GoogleAuth, http: HttpClient = HttpClient()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsRepository(store, scope)
    val sessions = SessionRepository(store, google, ::nowMillis)
    val bands = BandRepository(store, scope)
    val api = ApiClient(http, AppConfig.API_URL) { force -> sessions.validToken(force) }
    val sync = SyncEngine(api, bands, scope)

    private var loaded = false

    /** Loads persisted state once per process; later calls (e.g. after Android activity recreation) are no-ops. */
    suspend fun load() {
        if (loaded) return
        settings.load()
        sessions.load()
        sessions.session.value?.let { session ->
            bands.load(session.userId)
            sync.start()
        }
        loaded = true
    }

    suspend fun onSignedIn(idToken: String) {
        val session = sessions.signedIn(idToken)
        bands.load(session.userId)
        sync.start()
        sync.resumeAfterSignIn()
    }

    suspend fun signOut() {
        sync.stop()
        sessions.signOut()
    }

    /** Applies an operation locally, remembers the touched event for the "Continue" card, and schedules a sync. */
    fun dispatch(op: Operation): Rejection? {
        val rejection = bands.dispatch(op)
        if (rejection == null) {
            op.eventId?.let { eventId -> settings.update { it.copy(lastEditedEventId = eventId) } }
            sync.requestSync()
        }
        return rejection
    }
}
