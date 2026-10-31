package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

    // The exchange runs only after construction, when api exists.
    val sessions: SessionRepository = SessionRepository(store, google, ::nowMillis) { bearer -> api.createSession(bearer) }
    val bands = BandRepository(store, scope)

    /** The server no longer serves this app version (ITERATION_2 §2): the app shows "update to continue". */
    val updateRequired = MutableStateFlow(false)
    val api: ApiClient = ApiClient(
        http,
        AppConfig.API_URL,
        token = { refused -> sessions.validToken(forceRefresh = refused != null, refused = refused) },
        appVersion = AppConfig.VERSION_CODE,
        installation = messageChannel?.installationId,
        onUpdateRequired = { updateRequired.value = true },
        // In the app scope: signing out stops the sync loop, which may be the very caller.
        onSessionsRevoked = { scope.launch { account.signOutRevoked() } },
    )
    val uploads = AttachmentUploads(store, api, bands)

    /** Where event locations are, for the map (ITERATION_7 §8). */
    val places = PlacesLocator(store, api)

    // A band created here may be the user's first: the server keeps the device only while they are in one.
    val sync: SyncEngine = SyncEngine(api, bands, scope, uploads = uploads, onBandCreated = { messages.register() })
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

    private var loaded = false
    private val loadLock = Mutex()
    private var backgroundUser: String? = null

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
