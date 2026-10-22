package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RejectionCode
import kotlinx.coroutines.CoroutineExceptionHandler
import com.jacekgajek.kapelusz.model.Member
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.FlowPreview
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.auth.SessionRepository
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.domain.SharedPlace
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.Rejection
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
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
import kotlin.time.Duration.Companion.milliseconds

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
    val api: ApiClient = ApiClient(
        http,
        AppConfig.API_URL,
        token = { force -> sessions.validToken(force) },
        appVersion = AppConfig.VERSION_CODE,
        onUpdateRequired = { updateRequired.value = true },
    )
    val uploads = AttachmentUploads(store, api, bands)
    val sync = SyncEngine(api, bands, scope, uploads = uploads)

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

    /** Called after signing out, e.g. to cancel this user's reminders (Android). */
    var afterSignOut: () -> Unit = {}

    /** A place shared from Google Maps, waiting for "Set the location of…" (Android, ITERATION_2 §4.4). */
    val pendingSharedPlace = MutableStateFlow<SharedPlace?>(null)

    /** The user chose "New event at this place": the create dialog opens with it. */
    val pendingNewEventPlace = MutableStateFlow<SharedPlace?>(null)

    /** A gig to open, e.g. from a reminder notification: band id and event id. */
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

    /**
     * Calls [onChange] (debounced) whenever the settings, the bands or the signed-in user change, e.g. to schedule gig
     * reminders again right away (ITERATION_2 §5.2) instead of only when the app leaves the screen.
     */
    @OptIn(FlowPreview::class)
    fun watchReminderInputs(onChange: suspend (userId: String) -> Unit) {
        scope.launch {
            // Only with the bands loaded: before that there is nothing to remind of, and every alarm would be dropped.
            combine(settings.settings, bands.state, sessions.session) { _, state, session -> session?.userId?.takeIf { state is BandState.Ready } }
                .debounce(500.milliseconds)
                .collect { userId -> if (userId != null) onChange(userId) }
        }
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
        _signIns.value++
        sync.start()
        sync.resumeAfterSignIn()
    }.await()

    suspend fun signOut() {
        sync.stop()
        sessions.signOut()
        pendingOpenEvent.value = null
        afterSignOut()
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
     * Attaches a prepared file to a cost: the attachment is added like any change, and the file waits on the device
     * until the sync has uploaded it.
     */
    suspend fun attach(costId: Id, file: PreparedFile): Rejection? {
        val bandId = bands.currentBandId ?: return null
        val attachment = Attachment(newId(), file.kind, file.fileName, file.sizeBytes)
        // The file first, then the attachment (ITERATION_3 R4).
        try {
            try {
                uploads.enqueue(PendingUpload(bandId, costId, attachment.id), file.base64)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return Rejection(RejectionCode.CONFLICT, "Could not store the file on this device: $e", RejectionReason.STORAGE_FAILED)
            }
            // Another band opened meanwhile (the file was being stored): this attachment belongs to the first one.
            val rejection = if (bands.currentBandId == bandId) dispatch(AddAttachment(costId, attachment))
            else Rejection(RejectionCode.CONFLICT, "Another band was opened", RejectionReason.NOT_FOUND)
            if (rejection != null) {
                uploads.cancel(attachment.id)
                return rejection
            }
            sync.requestSync()
            return null
        } finally {
            uploads.published(attachment.id)
        }
    }

    /** Attaches the files picked while adding [costId]; runs in the app scope because the form closes on save. */
    fun attachAfterSave(costId: Id, files: List<PreparedFile>) {
        if (files.isNotEmpty()) scope.launch { files.forEach { attach(costId, it) } }
    }

    fun removeAttachment(costId: Id, attachmentId: Id): Rejection? {
        val rejection = dispatch(RemoveAttachment(costId, attachmentId))
        if (rejection == null) scope.launch { uploads.cancel(attachmentId) }
        return rejection
    }

    /** A URL to open an attachment; fails with [ApiException] `attachment_not_uploaded` while its file is not on the server. */
    suspend fun attachmentUrl(attachmentId: Id): Result<String> {
        val bandId = bands.currentBandId ?: return Result.failure(IllegalStateException("No band open"))
        // Throwable, not only Exception: browser network errors are JsException.
        return try {
            Result.success(api.attachmentUrl(bandId, attachmentId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Applies an operation locally and schedules a sync. */
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
        scope.launch { runCatching { api.sendPicture(googleToken) }.onSuccess { sync.requestSync() } }
    }

    fun dispatch(op: Operation): Rejection? {
        val rejection = bands.dispatch(op)
        if (rejection == null) {
            sync.requestSync()
        }
        return rejection
    }
}
