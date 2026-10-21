package com.jacekgajek.artsplit.app.sync

import com.jacekgajek.artsplit.api.ApiErrors
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.app.data.AttachmentUploads
import com.jacekgajek.artsplit.app.data.BandRepository
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.ops.Rejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A band that is gone for this user: [deleted] by its owner, or the user was removed from it. */
data class LostBand(val name: String, val deleted: Boolean)

enum class SyncStatus { IDLE, SYNCING, SYNCED, OFFLINE, NEEDS_SIGN_IN, UPDATE_REQUIRED, ERROR }

/** The server answered in a way the client cannot make progress with. */
class SyncProtocolException(message: String) : RuntimeException(message)

/**
 * Keeps the device and the server in step (VERSION_1 §5.2): creates bands made offline, pushes queued operations,
 * pulls the current band, and refreshes the band list. Runs on demand (after a change), every [interval] while the
 * app is in the foreground, and retries with backoff while offline.
 */
class SyncEngine(
    private val api: ApiClient,
    private val bands: BandRepository,
    private val scope: CoroutineScope,
    private val interval: Long = 30_000,
    /** Attachment files waiting for upload; none in tests that only cover operations. */
    private val uploads: AttachmentUploads? = null,
) {
    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 16)

    /** Operations the server refused (e.g. permissions changed meanwhile); shown to the user. */
    val rejections: SharedFlow<Rejection> = _rejections.asSharedFlow()

    private val _lostAccess = MutableSharedFlow<LostBand>(extraBufferCapacity = 4)

    /** Bands the user can no longer access (removed from the band, or the band was deleted). */
    val lostAccess: SharedFlow<LostBand> = _lostAccess.asSharedFlow()

    private val runLock = Mutex()
    private val wakeUp = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)
    private var loop: Job? = null

    /** Starts the periodic loop (idempotent). */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 0L
            while (true) {
                val ok = syncNow()
                backoff = if (ok) 0 else (if (backoff == 0L) 5_000 else backoff * 2).coerceAtMost(5 * 60_000)
                withTimeoutOrNull(if (ok) interval else backoff) { wakeUp.receive() }
                delay(DEBOUNCE)
            }
        }
    }

    /** Pauses the periodic loop, e.g. while the Android app is in the background. */
    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** The user signed in again: leave [SyncStatus.NEEDS_SIGN_IN] and sync right away. */
    fun resumeAfterSignIn() {
        if (_status.value == SyncStatus.NEEDS_SIGN_IN) _status.value = SyncStatus.IDLE
        requestSync()
    }

    /** Asks for a sync soon, e.g. after a local change. */
    fun requestSync() {
        wakeUp.trySend(Unit)
    }

    /**
     * One full round; returns false when it should be retried later. Bands are synced independently, so a problem
     * with one band never blocks the others.
     */
    suspend fun syncNow(): Boolean = runLock.withLock {
        // Waiting for the user to sign in again: retrying would only repeat silent renewal attempts (and on the web
        // possibly Google prompts) while hiding the "sign in again" state.
        if (_status.value == SyncStatus.NEEDS_SIGN_IN) return false
        // Retrying is pointless until the app is updated (which restarts it).
        if (_status.value == SyncStatus.UPDATE_REQUIRED) return false
        _status.value = SyncStatus.SYNCING
        var result = SyncStatus.SYNCED
        try {
            val current = bands.currentBandId
            val toSync = (bands.bandsToPush() + uploads?.bandsWithUploads().orEmpty() + listOfNotNull(current)).distinct()
            for (bandId in toSync) {
                val bandResult = syncBand(bandId, pull = bandId == current)
                if (bandResult == SyncStatus.NEEDS_SIGN_IN || bandResult == SyncStatus.OFFLINE || bandResult == SyncStatus.UPDATE_REQUIRED) {
                    // Affects every band: stop this round.
                    result = bandResult
                    break
                }
                if (bandResult == SyncStatus.ERROR) result = SyncStatus.ERROR
            }
            if (result == SyncStatus.SYNCED || result == SyncStatus.ERROR) bands.onBandList(api.me().bands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not only Exception: on the web, browser errors (e.g. "Failed to fetch") are JsException, a Throwable.
            result = classify(e)
        }
        _status.value = result
        result == SyncStatus.SYNCED
    }

    /**
     * The background sync (Android, VERSION_2 §5.1): every band the user belongs to, not only the open one, so
     * reminders and transfer notifications see current data. Bands not on this device yet are downloaded without
     * opening them. Returns false when it should be retried.
     */
    suspend fun syncAll(): Boolean = runLock.withLock {
        if (_status.value == SyncStatus.NEEDS_SIGN_IN || _status.value == SyncStatus.UPDATE_REQUIRED) return false
        try {
            bands.onBandList(api.me().bands)
            val ids = (bands.index.value?.bands.orEmpty().map { it.id } + bands.bandsToPush() + uploads?.bandsWithUploads().orEmpty()).distinct()
            for (bandId in ids) {
                if (bands.local(bandId) == null) {
                    val state = api.state(bandId, null) ?: continue
                    bands.storeFromServer(state.band, state.version)
                    continue
                }
                val result = syncBand(bandId, pull = true)
                if (result == SyncStatus.NEEDS_SIGN_IN || result == SyncStatus.OFFLINE || result == SyncStatus.UPDATE_REQUIRED) {
                    _status.value = result
                    return false
                }
            }
            bands.flush()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            classify(e).let { if (it == SyncStatus.NEEDS_SIGN_IN || it == SyncStatus.UPDATE_REQUIRED) _status.value = it }
            false
        }
    }

    private suspend fun syncBand(bandId: Id, pull: Boolean): SyncStatus = try {
        pushBand(bandId)
        // After the push: a file can only be uploaded once the server has its attachment.
        uploads?.uploadFor(bandId)
        if (pull) pullBand(bandId)
        SyncStatus.SYNCED
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        // Only the server's explicit "band not found for you" means access is gone; any other 404 (a wrong URL, an
        // unknown route) must never make the app delete bands from the device.
        val gone = e.code == ApiErrors.BAND_NOT_FOUND || e.code == ApiErrors.BAND_DELETED
        if (gone && bands.local(bandId)?.needsCreate == false) {
            // The band is gone or the user was removed from it: keep unsent changes as a backup and let go of it.
            val name = bands.loseAccess(bandId)
            _lostAccess.tryEmit(LostBand(name, deleted = e.code == ApiErrors.BAND_DELETED))
            SyncStatus.SYNCED
        } else {
            classify(e)
        }
    } catch (e: Throwable) {
        classify(e)
    }

    private fun classify(e: Throwable): SyncStatus = when {
        e is ApiException && e.code == ApiErrors.UPDATE_REQUIRED -> SyncStatus.UPDATE_REQUIRED
        else -> when (e) {
            is NeedsSignInException -> SyncStatus.NEEDS_SIGN_IN
            is ApiException, is SyncProtocolException -> SyncStatus.ERROR
            else -> SyncStatus.OFFLINE
        }
    }

    /** Downloads a band the user belongs to but that is not on this device yet, and opens it. */
    suspend fun download(bandId: Id) {
        val response = api.state(bandId, null) ?: return
        bands.adoptFromServer(response.band, response.version)
    }

    private suspend fun pushBand(bandId: Id) {
        // What is pushed must already be on disk, or a crash after the push could make later ops reuse sequence numbers.
        bands.flush()
        val local = bands.local(bandId) ?: return
        if (local.needsCreate) {
            val owner = local.base.owner ?: return
            val created = api.createBand(CreateBandRequest(local.base.id, local.base.name, local.base.currency, owner.id, owner.name))
            bands.onCreated(bandId, created.band, created.version)
        }
        while (true) {
            val batch = bands.local(bandId)?.pending?.take(BATCH_SIZE).orEmpty()
            if (batch.isEmpty()) return
            val response = api.push(bandId, batch)
            bands.onPushed(bandId, response)
            response.rejected.values.forEach { _rejections.tryEmit(it) }
            val handled = (response.accepted + response.rejected.keys + response.duplicates).toSet()
            if (batch.none { it.opId in handled }) throw SyncProtocolException("Server did not process the pushed operations")
        }
    }

    private suspend fun pullBand(bandId: Id) {
        val local = bands.local(bandId) ?: return
        if (local.needsCreate) return
        val response = api.state(bandId, local.version) ?: return
        bands.onPulled(bandId, response.band, response.version)
    }

    companion object {
        const val BATCH_SIZE = 200
        const val DEBOUNCE = 1_000L
    }
}
