package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.app.data.AttachmentUploads
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.nowMillis
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.Undo
import kotlin.time.Duration.Companion.milliseconds
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** A band that is gone for this user: [deleted] by its owner, or the user was removed from it. */
data class LostBand(val name: String, val deleted: Boolean)

/**
 * A band whose push the server refuses as a whole (ITERATION_3 R9), e.g. too many devices or a band grown too large:
 * sending it again cannot help. [message] is the server's.
 */
data class StuckPush(val status: Int, val code: String, val message: String)

enum class SyncStatus { IDLE, SYNCING, SYNCED, OFFLINE, NEEDS_SIGN_IN, UPDATE_REQUIRED, ERROR }

/** Leaving a band waits for its unsent changes and files; they could not be sent ([stuck]: the server refuses them). */
class LeaveBlocked(val stuck: Boolean) : RuntimeException("Unsent changes or files")

/** The server answered in a way the client cannot make progress with. */
class SyncProtocolException(message: String) : RuntimeException(message)

/**
 * Keeps the device and the server in step (ITERATION_1 §5.2): creates bands made offline, pushes queued operations,
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
    private val clock: () -> Long = ::nowMillis,
) {
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)

    /** What happened during a sync that others react to. */
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 16)

    /** Operations the server refused (e.g. permissions changed meanwhile); shown to the user. */
    val rejections: SharedFlow<Rejection> = _rejections.asSharedFlow()

    private val _lostAccess = MutableSharedFlow<LostBand>(extraBufferCapacity = 4)

    /** Bands the user can no longer access (removed from the band, or the band was deleted). */
    val lostAccess: SharedFlow<LostBand> = _lostAccess.asSharedFlow()

    private val _stuck = MutableStateFlow<Map<Id, StuckPush>>(emptyMap())

    /**
     * Bands whose unsent changes the server refuses as a whole (ITERATION_3 R9): their push is not retried until the
     * user discards them or the app starts again; pulls go on. Shown with an export or discard choice.
     */
    val stuck: StateFlow<Map<Id, StuckPush>> = _stuck.asStateFlow()

    private val runLock = Mutex()
    private val wakeUp = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)
    private var loop: Job? = null
    private var drain: Job? = null

    /**
     * The server will send this device a message when one of the user's bands changes (ITERATION_7 §2.3): the loop then
     * looks for news itself only every [MESSAGED_INTERVAL], in case a message got lost.
     */
    private var messaged: Boolean = false

    /** Told by the device's registration for messages whether the server will send them (then [messaged]). */
    fun messaged(on: Boolean) {
        messaged = on
    }

    /** Starts the periodic loop (idempotent). */
    fun start() {
        drain?.cancel()
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 0L
            while (true) {
                val ok = syncNow()
                backoff = if (ok) 0 else (if (backoff == 0L) 5_000 else backoff * 2).coerceAtMost(5 * 60_000)
                withTimeoutOrNull(waitAfter(ok, backoff).milliseconds) { wakeUp.receive() }
                delay(DEBOUNCE.milliseconds)
            }
        }
    }

    /** Until the next round: [interval] (or [MESSAGED_INTERVAL] when messages come) after a good one, else [backoff]. */
    internal fun waitAfter(ok: Boolean, backoff: Long): Long = if (!ok) {
        backoff
    } else if (messaged) {
        MESSAGED_INTERVAL
    } else {
        interval
    }

    /** Pauses the periodic loop, e.g. while the Android app is in the background. */
    fun stop() {
        loop?.cancel()
        loop = null
        drain?.cancel()
        drain = null
    }

    /**
     * The web app's tab was hidden (ITERATION_7 §2.6): what waits to be sent goes first (with backoff while offline,
     * and after Undo's hold), then nothing more until [start] when it is shown again.
     */
    fun pauseAfterSending() {
        loop?.cancel()
        loop = null
        if (drain?.isActive == true) return
        drain = scope.launch {
            var backoff = 5_000L

            // Not what can't go: bands whose changes the server refuses, or no session, or too old an app.
            fun waiting() = (bands.bandsToPush() - _stuck.value.keys).isNotEmpty() || uploads?.bandsWithUploads().orEmpty().isNotEmpty()
            fun blocked() = _status.value == SyncStatus.NEEDS_SIGN_IN || _status.value == SyncStatus.UPDATE_REQUIRED
            while (waiting() && !blocked()) {
                val ok = syncNow()
                if (!waiting() || blocked()) break
                delay((if (ok) 2_000 else backoff).milliseconds)
                backoff = if (ok) 5_000 else (backoff * 2).coerceAtMost(5 * 60_000)
            }
        }
    }

    /**
     * A message said [bandId] changed (ITERATION_7 §2.3). Only while the app is on screen (the loop runs); ignored
     * otherwise, as coming back to the screen syncs anyway. The open band syncs in a round of its own; another is
     * fetched quietly, downloaded if it isn't here yet, or let go of if it is gone for the user.
     */
    fun bandChanged(bandId: Id) {
        if (loop?.isActive != true) return
        if (bandId == bands.currentBandId) {
            requestSync()
            return
        }
        scope.launch {
            runSuspendCatching {
                runLock.withLock {
                    if (_status.value == SyncStatus.NEEDS_SIGN_IN || _status.value == SyncStatus.UPDATE_REQUIRED) return@withLock
                    if (bands.local(bandId) != null) {
                        syncBand(bandId, pull = true)
                    } else {
                        checkBandList(api.me().bands)
                        if (bands.local(bandId) == null && bands.index.value?.bands.orEmpty().any { it.id == bandId }) {
                            api.state(bandId, null)?.let { bands.storeFromServer(it.band, it.version) }
                        }
                    }
                }
            }
        }
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

    /** The user tapped the sync icon: it shows "syncing" at once, even when nothing waits to be sent. */
    fun syncAsked() {
        if (_status.value != SyncStatus.NEEDS_SIGN_IN && _status.value != SyncStatus.UPDATE_REQUIRED) _status.value = SyncStatus.SYNCING
        requestSync()
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
        var result = SyncStatus.SYNCED
        try {
            val current = bands.currentBandId
            val outgoing = (bands.bandsToPush() + uploads?.bandsWithUploads().orEmpty()).distinct()
            // "Syncing" only while something is sent: a round that only checks for news (every [interval]) keeps the
            // icon as it is, rather than blinking it, and redrawing the band's screens, each time.
            if (outgoing.isNotEmpty()) _status.value = SyncStatus.SYNCING
            val toSync = (outgoing + listOfNotNull(current)).distinct()
            for (bandId in toSync) {
                val bandResult = syncBand(bandId, pull = bandId == current)
                if (bandResult == SyncStatus.NEEDS_SIGN_IN || bandResult == SyncStatus.OFFLINE || bandResult == SyncStatus.UPDATE_REQUIRED) {
                    // Affects every band: stop this round.
                    result = bandResult
                    break
                }
                if (bandResult == SyncStatus.ERROR) result = SyncStatus.ERROR
            }
            if (result == SyncStatus.SYNCED || result == SyncStatus.ERROR) checkBandList(api.me().bands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not only Exception: on the web, browser errors (e.g. "Failed to fetch") are JsException, a Throwable.
            result = classify(e)
        }
        _status.value = shown(result)
        result == SyncStatus.SYNCED
    }

    /**
     * A band whose push is stuck is not in sync, even when it is not the open one (whose banner says why): the status
     * shows an error. The round still counts as done, since retrying sooner cannot help.
     */
    private fun shown(result: SyncStatus): SyncStatus = if (result == SyncStatus.SYNCED && _stuck.value.keys.any { bands.local(it) != null }) SyncStatus.ERROR else result

    /**
     * Every band the user belongs to, not only the open one: e.g. "sync soon" once the app has left the screen, or an event
     * link looking for its band. Bands not on this device yet are downloaded without opening them. Returns false when it
     * should be retried.
     */
    suspend fun syncAll(): Boolean = runLock.withLock {
        if (_status.value == SyncStatus.NEEDS_SIGN_IN || _status.value == SyncStatus.UPDATE_REQUIRED) return false
        try {
            checkBandList(api.me().bands)
            val ids = (bands.index.value?.bands.orEmpty().map { it.id } + bands.bandsToPush() + uploads?.bandsWithUploads().orEmpty()).distinct()
            var allOk = true
            for (bandId in ids) {
                val result = if (bands.local(bandId) == null) {
                    // One band failing to download must not keep the others from syncing.
                    runSuspendCatching {
                        api.state(bandId, null)?.let { bands.storeFromServer(it.band, it.version) }
                        SyncStatus.SYNCED
                    }.getOrElse { classify(it) }
                } else {
                    syncBand(bandId, pull = true)
                }
                if (result == SyncStatus.NEEDS_SIGN_IN || result == SyncStatus.OFFLINE || result == SyncStatus.UPDATE_REQUIRED) {
                    _status.value = backgroundShown(result)
                    return false
                }
                if (result != SyncStatus.SYNCED) allOk = false
            }
            bands.flush()
            if (allOk) _status.value = shown(SyncStatus.SYNCED)
            allOk
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            classify(e).let { if (it == SyncStatus.UPDATE_REQUIRED) _status.value = it }
            false
        }
    }

    /**
     * What a background round may show. Not "sign in again" (V5-M1 A6): in the background a token can't be renewed
     * (that needs the app on screen), and the foreground would then stop syncing until the user signs in by hand
     * although its own silent renewal would work.
     */
    private fun backgroundShown(result: SyncStatus): SyncStatus = if (result == SyncStatus.NEEDS_SIGN_IN) _status.value else result

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

            // An answer this app cannot read is not a network problem (ITERATION_3 R9).
            is ApiException, is SyncProtocolException, is SerializationException -> SyncStatus.ERROR

            else -> SyncStatus.OFFLINE
        }
    }

    /**
     * Updates the band list, and checks every band still on this device that it no longer has (ITERATION_3 R9): the
     * check finds it deleted or access lost, says so, and removes it.
     */
    private suspend fun checkBandList(listed: List<BandSummary>) {
        val unlisted = bands.unlisted(listed)
        bands.onBandList(listed)
        for (bandId in unlisted) syncBand(bandId, pull = true)
    }

    /**
     * Drops the unsent changes of a [stuck] band (a backup stays on the device, ITERATION_3 R9) and shows the band as
     * the server has it; new changes are pushed again.
     */
    suspend fun discardStuck(bandId: Id) {
        runLock.withLock {
            val discarded = bands.discardPending(bandId)
            uploads?.keepUnsent(discarded.mapNotNull { (it.op as? AddAttachment)?.attachment?.id }.toSet())
            _stuck.update { it - bandId }
            // Offline: the next sync pulls it.
            runSuspendCatching { pullBand(bandId) }
        }
        requestSync()
    }

    /**
     * Leaves [bandId] between sync rounds, so none of them meets the band already left and reports it lost: its unsent
     * changes (held for Undo too) and files go first, then the server unlinks the account and the band is forgotten.
     * Throws [LeaveBlocked] when they can't be sent, and network errors and refusals as they come.
     */
    suspend fun leave(bandId: Id) {
        runLock.withLock {
            if (bandId in _stuck.value) throw LeaveBlocked(stuck = true)
            bands.local(bandId)?.let { local -> bands.release(bandId, *local.pending.map { it.opId }.toTypedArray()) }
            pushBand(bandId)
            if (bandId in _stuck.value || bands.local(bandId)?.pending?.isNotEmpty() == true) throw LeaveBlocked(stuck = bandId in _stuck.value)
            uploads?.uploadFor(bandId)
            if (uploads?.bandsWithUploads()?.contains(bandId) == true) throw LeaveBlocked(stuck = false)
            try {
                api.leaveBand(bandId)
            } catch (e: ApiException) {
                // Not a member any more: an earlier attempt went through, its answer lost on the way.
                if (e.code != ApiErrors.BAND_NOT_FOUND) throw e
            }
            bands.forgetDeleted(bandId)
        }
    }

    /** Downloads a band the user belongs to but that is not on this device yet, and opens it. */
    suspend fun download(bandId: Id) {
        val response = api.state(bandId, null) ?: return
        bands.adoptFromServer(response.band, response.version)
    }

    private suspend fun storedOrStop() {
        if (!bands.flush()) throw SyncProtocolException("This device could not store the band; not pushing until it can")
    }

    private suspend fun pushBand(bandId: Id) {
        // What is pushed must already be on disk, or a crash after the push could make later ops reuse sequence numbers:
        // if the device cannot store it now, nothing is pushed (ITERATION_3 R4). Checked again before every batch.
        storedOrStop()
        val local = bands.local(bandId) ?: return
        if (local.needsCreate) {
            val owner = local.base.owner ?: return
            val created = api.createBand(CreateBandRequest(local.base.id, local.base.name, local.base.currency, owner.id, owner.name))
            bands.onCreated(bandId, created.band, created.version)
            _events.emit(SyncEvent.BandCreated(bandId))
        }
        // Refused as a whole before: not again until the user decides (ITERATION_3 R9).
        if (bandId in _stuck.value) return
        while (true) {
            val current = bands.local(bandId) ?: return
            val batch = batchOf(current.pending, current.holds, clock())
            if (batch.isEmpty()) return
            // Sent, so no longer taken back by an Undo, whatever the clock says (a hold may only have expired).
            bands.release(bandId, *batch.map { it.opId }.toTypedArray())
            // Ops added since the last check (a big push goes in batches) must be on disk too (V5-M1 A9a).
            storedOrStop()
            val response = try {
                api.push(bandId, batch)
            } catch (e: ApiException) {
                // The request itself refused (not single operations): the same push would be refused again.
                if (e.status != 400 && e.status != 413) throw e
                _stuck.update { it + (bandId to StuckPush(e.status, e.code, e.message.orEmpty())) }
                return
            }
            _stuck.update { it - bandId }
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

        /** How often the app on screen looks for news itself when messages tell it about changes (ITERATION_7 §2.3). */
        const val MESSAGED_INTERVAL = 5 * 60_000L

        /**
         * The next ops to push: at most [BATCH_SIZE], and no more undos than the server takes at once (ITERATION_3 R6);
         * none from the first one still [held][LocalBand.holds] on, as later ones may build on it (ITERATION_5 §2).
         */
        internal fun batchOf(pending: List<OpEnvelope>, holds: Map<Id, Long> = emptyMap(), now: Long = 0): List<OpEnvelope> {
            var undos = 0
            return pending.takeWhile { (holds[it.opId] ?: 0) <= now }.take(BATCH_SIZE).takeWhile { envelope -> envelope.op !is Undo || ++undos <= Limits.MAX_UNDOS_PER_PUSH }
        }
    }
}

/** Something a sync did that others react to. */
sealed interface SyncEvent {
    /** A band made on this device is now on the server: maybe the user's first, so registering for messages pays. */
    data class BandCreated(val bandId: Id) : SyncEvent
}
