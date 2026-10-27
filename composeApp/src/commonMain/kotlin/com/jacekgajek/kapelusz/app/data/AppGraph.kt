package com.jacekgajek.kapelusz.app.data

import kotlinx.coroutines.CoroutineExceptionHandler
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.Rejection
import io.ktor.client.HttpClient
import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide objects, created once per app start (no DI framework). */
class AppGraph(store: KeyValueStore, google: GoogleAuth, http: HttpClient = HttpClient()) {
    // A failure in the app's own background work is logged, never a crash (ITERATION_3 R4).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> println("Unhandled in the app scope: $e") })
    val settings = SettingsRepository(store, scope)
    // The exchange runs only after construction, when api exists.
    val sessions: SessionRepository = SessionRepository(store, google, ::nowMillis) { bearer -> api.createSession(bearer) }
    val bands = BandRepository(store, scope)
    /** The server no longer serves this app version (ITERATION_2 §2): the app shows "update to continue". */
    val updateRequired = MutableStateFlow(false)

    /** The user was signed out because of "sign out on all devices" (ITERATION_3 R6); the sign-in screen says so. */
    val signedOutEverywhere = MutableStateFlow(false)
    val api: ApiClient = ApiClient(
        http,
        AppConfig.API_URL,
        token = { refused -> sessions.validToken(forceRefresh = refused != null, refused = refused) },
        appVersion = AppConfig.VERSION_CODE,
        onUpdateRequired = { updateRequired.value = true },
        // In the app scope: signing out stops the sync loop, which may be the very caller.
        onSessionsRevoked = { scope.launch { signOutRevoked() } },
    )
    val uploads = AttachmentUploads(store, api, bands)
    val sync = SyncEngine(api, bands, scope, uploads = uploads)
    val attachments = Attachments(bands, uploads, api, sync, scope, ::dispatch, download = { url ->
        // A presigned link: nothing of the app's own (no token) goes with it.
        val response = http.get(url)
        check(response.status.isSuccess()) { "Download failed: ${response.status}" }
        response.bodyAsBytes()
    })

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

    /** The sign-ins the UI has reacted to already (it is composed again on rotation and language changes). */
    var handledSignIns = 0

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
    }

    private val loadLock = Mutex()
    private var backgroundUser: String? = null

    /**
     * The band whose screens the navigation was last set up for by whoever switched to it (an event link opening one of
     * its events): the "another band is shown" reset then leaves that navigation alone (V5-M1 C2b).
     */
    var navigatedForBand: String? = null

    /** Photos or PDFs shared to Kapelusz (Android, ITERATION_6 §1), waiting for "Attach to…". */
    val sharedFiles = MutableStateFlow<SharedFiles?>(null)

    /** Shared files on their way to a new cost or collected entry, which takes them as its files to attach. */
    val sharedHandover = MutableStateFlow<SharedHandover?>(null)

    /** Shared files just attached to something saved: the event's snackbar offers to take them off again. */
    val sharedAttached = MutableStateFlow<SharedAttached?>(null)

    /** The shared files handed to a new cost ([toCost]) or collected entry of [eventId], once; empty if none. */
    fun takeSharedFiles(eventId: String, toCost: Boolean): List<PreparedFile> {
        val handover = sharedHandover.value?.takeIf { it.eventId == eventId && it.toCost == toCost } ?: return emptyList()
        sharedHandover.value = null
        return handover.files
    }

    /** An event to show at one of its sections once open (event id, section), e.g. where shared files were attached. */
    val pendingEventSection = MutableStateFlow<Pair<String, String>?>(null)

    /** An event link's event id (ITERATION_5 §10), opened once the user is signed in and their bands are known. */
    val pendingEventLink = MutableStateFlow<String?>(null)

    /** A gig to open, e.g. from an event link: band id and event id. */
    val pendingOpenEvent = MutableStateFlow<Pair<String, String>?>(null)

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

    /** Settings for a background job, which may run without the app having started (loads them if needed). */
    suspend fun settingsForBackground(): AppSettings {
        if (!loaded) settings.load()
        return settings.settings.value
    }

    /**
     * Runs in the app scope, not the caller's: the sign-in screen leaves the composition as soon as the session
     * appears, which would cancel the loading half-way and leave a blank screen. The bands are loaded before the
     * session is published, so the UI switches straight to the loaded band.
     */
    suspend fun onSignedIn(idToken: String) = scope.async {
        val userId = Session.fromIdToken(idToken).userId
        bands.load(userId)
        uploads.load(userId)
        sessions.signedIn(idToken)
        signedOutEverywhere.value = false
        _signIns.value++
        sync.start()
        sync.resumeAfterSignIn()
    }.await()

    /**
     * Signs out in the app's own scope (V5-M1 A5c): the screen that asked goes away as soon as the session does, and
     * its scope with it, which must not stop the rest of signing out.
     */
    fun signOutInBackground() {
        scope.launch { signOut() }
    }

    suspend fun signOut() {
        sync.stop()
        sessions.signOut()
        pendingOpenEvent.value = null
    }

    /**
     * "Sign out on all devices" (ITERATION_3 R6): the server ends every session of the user, this device's included,
     * then this device signs out as [signOut] does. Throws (and stays signed in) when the server could not be told.
     */
    suspend fun signOutEverywhere() {
        api.revokeSessions()
        signOut()
    }

    /**
     * The server refuses this device's tokens after "sign out on all devices" (here or elsewhere): signs out as
     * [signOut] does, which also keeps Google from signing in again silently. Several requests may be refused at once.
     */
    private suspend fun signOutRevoked() {
        if (sessions.session.value == null) return
        signedOutEverywhere.value = true
        signOut()
    }

    /**
     * Deletes the open band for everyone (owner only; needs the server, except for a band never uploaded). Network
     * errors and refusals surface as exceptions; on success the band is gone from this device too.
     */
    suspend fun deleteBand() {
        val bandId = bands.currentBandId ?: return
        if (bands.local(bandId)?.needsCreate != true) api.deleteBand(bandId)
        bands.forgetDeleted(bandId)
    }

    /**
     * Leaves the open band (not as its owner), see [SyncEngine.leave]. Network errors and refusals surface as
     * exceptions; on success the band is gone from this device. In the app scope: the band's screens go away as soon
     * as it is forgotten, which must not cancel it half-way.
     */
    suspend fun leaveBand() {
        val bandId = bands.currentBandId ?: return
        scope.async { sync.leave(bandId) }.await()
    }

    /**
     * Discards the unsent changes of a band the server refuses them for (ITERATION_3 R9); in the app scope, since the
     * banner asking for it leaves as soon as the band is no longer stuck.
     */
    fun discardStuck(bandId: String) {
        scope.launch { sync.discardStuck(bandId) }
    }

    /** Changes or attachment files still waiting to be sent to the server. */
    fun hasUnsentWork(): Boolean = bands.bandsToPush().isNotEmpty() || uploads.pending.value.isNotEmpty()

    private var pictureSent = false

    /**
     * Once per app run, while the user's member has no photo: the Google token kept from signing in carries it, so
     * members signed in before photos existed get theirs without signing in again (ITERATION_2 §7.4).
     */
    fun sendPictureIfMissing(me: Member?) {
        if (pictureSent || me == null || me.pictureUrl != null) return
        val googleToken = sessions.session.value?.idToken ?: return
        pictureSent = true
        scope.launch { runSuspendCatching { api.sendPicture(googleToken) }.onSuccess { sync.requestSync() } }
    }

    /** Applies an operation locally and schedules a sync. */
    fun dispatch(op: Operation): Rejection? {
        val rejection = bands.dispatch(op)
        if (rejection == null) {
            sync.requestSync()
        }
        return rejection
    }

    /**
     * Applies an operation locally but holds it back from syncing for [holdFor] ms, while its Undo snackbar shows
     * (ITERATION_5 §2); [onHeld] gets it. Returns the rejection, if any.
     */
    fun dispatchHeld(op: Operation, holdFor: Long, onHeld: (HeldOp) -> Unit): Rejection? {
        val bandId = bands.currentBandId ?: error("No band open")
        bands.dispatch(op, holdFor)?.let { return it }
        onHeld(HeldOp(bandId, bands.lastOpId!!))
        return null
    }

    /** Lets a held operation sync now (its snackbar is gone). */
    fun release(held: HeldOp) {
        bands.release(held.bandId, held.opId)
        sync.requestSync()
    }

    /** Takes a held operation back; false when it may already be on its way (then only the history can undo it). */
    fun withdraw(held: HeldOp): Boolean {
        if (!bands.withdraw(held.bandId, held.opId)) return false
        // What was queued behind it waited for it, and goes now.
        sync.requestSync()
        return true
    }
}

/** An operation held back from syncing while its Undo snackbar shows. */
data class HeldOp(val bandId: Id, val opId: Id)

/**
 * Files shared to Kapelusz: those ready to attach, what could not be used ([PickResult] other than Picked), and how many
 * more were shared than any target takes ([tooMany], not read).
 */
class SharedFiles(val files: List<PreparedFile>, val skipped: List<PickResult>, val tooMany: Int = 0)

/** Shared [files] for a new cost ([toCost]) or a new collected entry of [eventId]. */
class SharedHandover(val eventId: String, val toCost: Boolean, val files: List<PreparedFile>)

/**
 * Attachments [attachmentIds] just added to [owner] from shared files, in event [eventId] of band [bandId]; [failed] of
 * the files could not be attached.
 */
data class SharedAttached(val bandId: String, val eventId: String, val owner: AttachmentOwner, val attachmentIds: List<String>, val failed: Int)
