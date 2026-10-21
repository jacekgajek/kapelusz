package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.auth.GoogleAuth
import com.jacekgajek.artsplit.app.auth.Session
import com.jacekgajek.artsplit.app.auth.SessionRepository
import com.jacekgajek.artsplit.app.sync.ApiClient
import com.jacekgajek.artsplit.app.sync.SyncEngine
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.Rejection
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide objects, created once per app start (no DI framework). */
class AppGraph(store: KeyValueStore, google: GoogleAuth, http: HttpClient = HttpClient()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsRepository(store, scope)
    val sessions = SessionRepository(store, google, ::nowMillis)
    val bands = BandRepository(store, scope)
    val api = ApiClient(http, AppConfig.API_URL) { force -> sessions.validToken(force) }
    val sync = SyncEngine(api, bands, scope)

    private var loaded = false

    private val _signIns = MutableStateFlow(0)

    /** An invite code from a join link (web `/join/CODE`, Android App Link), waiting to be shown to the user. */
    val pendingJoinCode = MutableStateFlow<String?>(null)

    /** Accepts an invite and opens the band. Network errors and refusals surface as exceptions. */
    suspend fun joinBand(code: String) {
        val joined = api.acceptInvite(code)
        bands.adoptFromServer(joined.band, joined.version)
        sync.requestSync()
    }

    /** Counts interactive sign-ins in this process; the UI starts from the event list after each one. */
    val signIns: StateFlow<Int> = _signIns.asStateFlow()

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

    /**
     * Runs in the app scope, not the caller's: the sign-in screen leaves the composition as soon as the session
     * appears, which would cancel the loading half-way and leave a blank screen. The bands are loaded before the
     * session is published, so the UI switches straight to the loaded band.
     */
    suspend fun onSignedIn(idToken: String) = scope.async {
        val userId = Session.fromIdToken(idToken).userId
        bands.load(userId)
        sessions.signedIn(idToken)
        _signIns.value++
        sync.start()
        sync.resumeAfterSignIn()
    }.await()

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
