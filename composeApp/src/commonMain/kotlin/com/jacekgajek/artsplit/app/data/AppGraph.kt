package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.auth.GoogleAuth
import com.jacekgajek.artsplit.app.auth.Session
import com.jacekgajek.artsplit.app.auth.SessionRepository
import com.jacekgajek.artsplit.app.sync.ApiClient
import com.jacekgajek.artsplit.app.sync.SyncEngine
import com.jacekgajek.artsplit.app.platform.PreparedFile
import com.jacekgajek.artsplit.model.Attachment
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.ops.AddAttachment
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.RemoveAttachment
import com.jacekgajek.artsplit.ops.Rejection
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide objects, created once per app start (no DI framework). */
class AppGraph(store: KeyValueStore, google: GoogleAuth, http: HttpClient = HttpClient()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsRepository(store, scope)
    // The exchange runs only after construction, when api exists.
    val sessions: SessionRepository = SessionRepository(store, google, ::nowMillis) { bearer -> api.createSession(bearer) }
    val bands = BandRepository(store, scope)
    /** The server no longer serves this app version (VERSION_2 §2): the app shows "update to continue". */
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

    /** Loads persisted state once per process; later calls (e.g. after Android activity recreation) are no-ops. */
    suspend fun load() {
        if (loaded) return
        settings.load()
        sessions.load()
        sessions.session.value?.let { session ->
            bands.load(session.userId)
            uploads.load(session.userId)
            sync.start()
        }
        loaded = true
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
        dispatch(AddAttachment(costId, attachment))?.let { return it }
        uploads.enqueue(PendingUpload(bandId, costId, attachment.id), file.base64)
        sync.requestSync()
        return null
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
