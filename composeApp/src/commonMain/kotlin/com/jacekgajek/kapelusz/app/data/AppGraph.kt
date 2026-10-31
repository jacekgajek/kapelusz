package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.ApiSignal
import com.jacekgajek.kapelusz.app.sync.ApiTransport
import com.jacekgajek.kapelusz.app.sync.SessionApi
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.sync.SyncEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide objects, created once per app start (no DI framework), and loading them. The work is theirs: [account]
 * signs in and out, [membership] joins and leaves bands, [changes] applies the user's edits, [incoming] holds links and
 * shared files for the UI.
 */
class AppGraph(
    store: KeyValueStore,
    google: GoogleAuth,
    http: HttpClient = HttpClient(),
    /** Firebase sync messages (ITERATION_7 §2.3); null on the web. */
    messageChannel: MessageChannel? = null,
) {
    // A failure in the app's own background work is logged, never a crash (ITERATION_3 R4).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> println("Unhandled in the app scope: $e") })
    val settings = SettingsRepository(store, scope)

    private val transport = ApiTransport(
        http,
        AppConfig.API_URL,
        appVersion = AppConfig.VERSION_CODE,
        installation = messageChannel?.installationId,
    )
    val sessions: SessionRepository = SessionRepository(store, google, ::nowMillis, SessionApi(transport)::createSession)
    val bands = BandRepository(BandStore(store, scope))

    /** The server no longer serves this app version (ITERATION_2 §2); part of [state]. */
    private val updateRequired = MutableStateFlow(false)
    val api: ApiClient = ApiClient(transport) { refused -> sessions.validToken(forceRefresh = refused != null, refused = refused) }
    val uploads = AttachmentUploads(store, api, bands)

    /** Where event locations are, for the map (ITERATION_7 §8). */
    val places = PlacesLocator(store, api)

    val sync: SyncEngine = SyncEngine(api, bands, scope, uploads = uploads)
    val messages: DeviceMessages = DeviceMessages(messageChannel, sessions, api, sync, scope)
    val changes = Changes(bands, sync)
    val incoming = Incoming()
    val account: Account = Account(scope, sessions, api, bands, uploads, sync, messages, incoming)
    val membership = Membership(scope, api, bands, sync, messages)
    val attachments = Attachments(bands, uploads, api, sync, scope, changes::dispatch, download = { url ->
        // A presigned link: nothing of the app's own (no token) goes with it.
        val response = http.get(url)
        check(response.status.isSuccess()) { "Download failed: ${response.status}" }
        response.bodyAsBytes()
    })

    /** What the user sees: the one thing the app's root switches on; [currentState] is the same now, for a first frame. */
    val state: Flow<AppState> = combine(updateRequired, sessions.session, bands.state, account.signedOutEverywhere, ::appState)

    /** The state as it is this instant, read from its parts: what [App] starts from, so nothing stale shows first. */
    val currentState: AppState
        get() = appState(updateRequired.value, sessions.session.value, bands.state.value, account.signedOutEverywhere.value)

    private var loaded = false
    private val loadLock = Mutex()
    private var backgroundUser: String? = null

    init {
        // Subscribed before anything can emit (a signal with nobody listening would be lost). Each signal is handled in
        // a launch of its own: one that fails or takes long (signing out stops the sync loop, which may be the very
        // caller) must neither block the next nor end the subscription.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.signals.collect { signal ->
                scope.launch {
                    when (signal) {
                        ApiSignal.UpdateRequired -> updateRequired.value = true
                        ApiSignal.SessionsRevoked -> account.signOutRevoked()
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sync.events.collect { if (it is SyncEvent.BandCreated) scope.launch { messages.register() } }
        }
    }

    /** Loads persisted state once per process; later calls (e.g. after Android activity recreation) are no-ops. */
    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        // A background job may have loaded this user's data already (and may be syncing it): keep it, only start.
        if (backgroundUser == null) {
            settings.load()
            sessions.load()
            sessions.session.value?.let { session ->
                bands.load(session.userId)
                uploads.load(session.userId)
            }
        }
        if (sessions.session.value != null) sync.start()
        loaded = true
        messages.register()
    }

    /**
     * Loads what a background job needs (settings, session, bands, waiting uploads) without starting the foreground
     * sync loop; the app's own [load] later keeps it. Returns the signed-in user, if any.
     */
    suspend fun loadForBackground(): String? = loadLock.withLock {
        if (!loaded && backgroundUser == null) {
            settings.load()
            sessions.load()
            sessions.session.value?.userId?.let { userId ->
                bands.load(userId)
                uploads.load(userId)
                backgroundUser = userId
            }
        }
        sessions.session.value?.userId
    }

    /** Changes or attachment files still waiting to be sent to the server. */
    fun hasUnsentWork(): Boolean = bands.bandsToPush().isNotEmpty() || uploads.pending.value.isNotEmpty()
}
