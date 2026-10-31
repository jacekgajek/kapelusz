package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.app.attachments.AttachmentUploads
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.util.nowMillis
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
 * When the device syncs (REFACTORING.md 3.5): the periodic loop with its backoff, the hidden tab's drain, the lock that
 * keeps rounds apart, and the status the icon shows. What a round does is [SyncRound]'s; this class decides when it
 * runs and for which bands, and tells the outcome.
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
    private val round = SyncRound(api, bands, uploads, clock)

    /** What happened during a sync that others react to. */
    val events: SharedFlow<SyncEvent> get() = round.events

    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Operations the server refused one by one during a push (e.g. a role changed meanwhile): told to the user. */
    val rejections: SharedFlow<Rejection> get() = round.rejections

    /** Bands this device had to let go of: deleted, or the user removed from them. */
    val lostAccess: SharedFlow<LostBand> get() = round.lostAccess

    /**
     * Bands whose unsent changes the server refuses as a whole (ITERATION_3 R9): their push is not retried until the
     * user discards them or the app starts again; pulls go on. Shown with an export or discard choice.
     */
    val stuck: StateFlow<Map<Id, StuckPush>> get() = round.stuck

    private val runLock = Mutex()
    private val wakeUp = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    private val _state = MutableStateFlow<SyncState>(SyncState.Stopped)

    /** Where the loop is; see [SyncState]. */
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private var roundJob: Job? = null
    private var waitJob: Job? = null
    private val blocked: Boolean get() = _state.value is SyncState.Blocked

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
    fun start() = send(SyncInput.Start)

    /** Pauses the periodic loop, e.g. while the Android app is in the background; a round under way is cut short. */
    fun stop() = send(SyncInput.Stop)

    /**
     * The web app's tab was hidden (ITERATION_7 §2.6): what waits to be sent goes first (with backoff while offline;
     * a held operation is waited for), then nothing more until [start] when it is shown again.
     */
    fun pauseAfterSending() = send(SyncInput.Hide)

    /** Until the next round: [interval] (or [MESSAGED_INTERVAL] when messages come) after a good one, else [backoff]. */
    internal fun waitAfter(ok: Boolean, backoff: Long): Long = rules().waitAfter(ok, backoff)

    /** Something waits to be sent that can go: not bands whose changes the server refuses. */
    private fun waiting(): Boolean {
        val unsent = bands.bandsToPush() - round.stuck.value.keys
        return unsent.isNotEmpty() || uploads?.bandsWithUploads().orEmpty().isNotEmpty()
    }

    private fun rules() = SyncRules(sending = waiting(), messaged = messaged, interval = interval, messagedInterval = MESSAGED_INTERVAL)

    /** The only way the loop moves: [reduce] says where it goes, and what to do is done here. */
    private fun send(input: SyncInput) {
        val (next, effect) = reduce(_state.value, input, rules())
        _state.value = next
        // A wait belongs between rounds; elsewhere a leftover one would only consume a wake.
        if (!next.betweenRounds) waitJob?.cancel()
        when (effect) {
            Effect.RunRound -> roundJob = scope.launch { syncNow() }
            is Effect.WaitThenRun -> waitJob = scope.launch { waitThenRun(effect) }
            Effect.Cancel -> roundJob?.cancel()
            Effect.None -> Unit
        }
    }

    private suspend fun waitThenRun(effect: Effect.WaitThenRun) {
        if (effect.wakeable) {
            // Until the time is up, or a wake comes first; then a breath, so quick taps make one round.
            withTimeoutOrNull(effect.after.milliseconds) { wakeUp.receive() }
            delay(DEBOUNCE.milliseconds)
        } else {
            delay(effect.after.milliseconds)
        }
        send(SyncInput.Wake)
    }

    /**
     * A message said [bandId] changed (ITERATION_7 §2.3). Only while the app is on screen (the loop runs); ignored
     * otherwise, as coming back to the screen syncs anyway. The open band syncs in a round of its own; another is
     * fetched quietly, downloaded if it isn't here yet, or let go of if it is gone for the user.
     */
    fun bandChanged(bandId: Id) {
        if (_state.value !is SyncState.Idle && _state.value !is SyncState.Running) return
        if (bandId == bands.currentBandId) {
            requestSync()
            return
        }
        scope.launch {
            runSuspendCatching {
                runLock.withLock {
                    if (blocked) return@withLock
                    if (bands.local(bandId) != null) {
                        round.syncBand(bandId, pull = true)
                    } else {
                        round.checkBandList(api.me().bands)
                        if (bands.local(bandId) == null && bands.index.value?.bands.orEmpty().any { it.id == bandId }) {
                            round.fetchBand(bandId)
                        }
                    }
                }
            }
        }
    }

    /** The user signed in again: leave [SyncStatus.NEEDS_SIGN_IN] and sync right away (when the loop runs). */
    fun resumeAfterSignIn() {
        if (_status.value == SyncStatus.NEEDS_SIGN_IN) _status.value = SyncStatus.IDLE
        send(SyncInput.SignedIn)
    }

    /** Asks for a sync soon, e.g. after a local change. */
    fun requestSync() {
        wakeUp.trySend(Unit)
    }

    /** The user tapped the sync icon: it shows "syncing" at once, even when nothing waits to be sent. */
    fun syncAsked() {
        if (!blocked) _status.value = SyncStatus.SYNCING
        requestSync()
    }

    /**
     * One full round; returns false when it should be retried later. Bands are synced independently, so a problem
     * with one band never blocks the others.
     */
    suspend fun syncNow(): Boolean {
        val result = runRound() ?: return false
        // Also a round run by hand: what blocks every round (sign in again, update the app) stops the loop too.
        send(SyncInput.RoundDone(result, waiting()))
        return result == SyncStatus.SYNCED
    }

    /** The round under the lock; null when none may run (waiting for the user to sign in again, or to update the app). */
    private suspend fun runRound(): SyncStatus? = runLock.withLock {
        // Waiting for the user to sign in again: retrying would only repeat silent renewal attempts (and on the web
        // possibly Google prompts) while hiding the "sign in again" state. Pointless until an update too (which restarts).
        if (blocked) return@withLock null
        var result = SyncStatus.SYNCED
        try {
            val current = bands.currentBandId
            val outgoing = (bands.bandsToPush() + uploads?.bandsWithUploads().orEmpty()).distinct()
            // "Syncing" only while something is sent: a round that only checks for news (every [interval]) keeps the
            // icon as it is, rather than blinking it, and redrawing the band's screens, each time.
            if (outgoing.isNotEmpty()) _status.value = SyncStatus.SYNCING
            val toSync = (outgoing + listOfNotNull(current)).distinct()
            for (bandId in toSync) {
                val bandResult = round.syncBand(bandId, pull = bandId == current)
                if (bandResult == SyncStatus.NEEDS_SIGN_IN || bandResult == SyncStatus.OFFLINE || bandResult == SyncStatus.UPDATE_REQUIRED) {
                    // Affects every band: stop this round.
                    result = bandResult
                    break
                }
                if (bandResult == SyncStatus.ERROR) result = SyncStatus.ERROR
            }
            if (result == SyncStatus.SYNCED || result == SyncStatus.ERROR) round.checkBandList(api.me().bands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not only Exception: on the web, browser errors (e.g. "Failed to fetch") are JsException, a Throwable.
            result = round.classify(e)
        }
        _status.value = shown(result)
        result
    }

    /**
     * A band whose push is stuck is not in sync, even when it is not the open one (whose banner says why): the status
     * shows an error. The round still counts as done, since retrying sooner cannot help.
     */
    private fun shown(result: SyncStatus): SyncStatus = if (result == SyncStatus.SYNCED && round.stuck.value.keys.any { bands.local(it) != null }) SyncStatus.ERROR else result

    /**
     * Every band the user belongs to, not only the open one: e.g. "sync soon" once the app has left the screen, or an event
     * link looking for its band. Bands not on this device yet are downloaded without opening them. Returns false when it
     * should be retried.
     */
    suspend fun syncAll(): Boolean = runLock.withLock {
        if (blocked) return false
        try {
            round.checkBandList(api.me().bands)
            val ids = (bands.index.value?.bands.orEmpty().map { it.id } + bands.bandsToPush() + uploads?.bandsWithUploads().orEmpty()).distinct()
            var allOk = true
            for (bandId in ids) {
                val result = if (bands.local(bandId) == null) {
                    // One band failing to download must not keep the others from syncing.
                    runSuspendCatching {
                        round.fetchBand(bandId)
                        SyncStatus.SYNCED
                    }.getOrElse { round.classify(it) }
                } else {
                    round.syncBand(bandId, pull = true)
                }
                if (result == SyncStatus.NEEDS_SIGN_IN || result == SyncStatus.OFFLINE || result == SyncStatus.UPDATE_REQUIRED) {
                    _status.value = backgroundShown(result)
                    if (result == SyncStatus.UPDATE_REQUIRED) send(SyncInput.RoundDone(result, waiting = false))
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
            round.classify(e).let {
                if (it == SyncStatus.UPDATE_REQUIRED) {
                    _status.value = it
                    send(SyncInput.RoundDone(it, waiting = false))
                }
            }
            false
        }
    }

    /**
     * What a background round may show. Not "sign in again" (V5-M1 A6): in the background a token can't be renewed
     * (that needs the app on screen), and the foreground would then stop syncing until the user signs in by hand
     * although its own silent renewal would work.
     */
    private fun backgroundShown(result: SyncStatus): SyncStatus = if (result == SyncStatus.NEEDS_SIGN_IN) _status.value else result

    /**
     * Drops the unsent changes of a [stuck] band (a backup stays on the device, ITERATION_3 R9) and shows the band as
     * the server has it; new changes are pushed again.
     */
    suspend fun discardStuck(bandId: Id) {
        runLock.withLock {
            val discarded = bands.discardPending(bandId)
            uploads?.keepUnsent(discarded.mapNotNull { (it.op as? AddAttachment)?.attachment?.id }.toSet())
            round.unstick(bandId)
            // Offline: the next sync pulls it.
            runSuspendCatching { round.pullBand(bandId) }
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
            if (bandId in round.stuck.value) throw LeaveBlocked(stuck = true)
            bands.local(bandId)?.let { local -> bands.release(bandId, *local.pending.map { it.opId }.toTypedArray()) }
            round.pushBand(bandId)
            val stuckNow = bandId in round.stuck.value
            if (stuckNow || bands.local(bandId)?.pending?.isNotEmpty() == true) throw LeaveBlocked(stuck = stuckNow)
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

    companion object {
        const val DEBOUNCE = 1_000L

        /** How often the app on screen looks for news itself when messages tell it about changes (ITERATION_7 §2.3). */
        const val MESSAGED_INTERVAL = 5 * 60_000L
    }
}

/** Something a sync did that others react to. */
sealed interface SyncEvent {
    /** A band made on this device is now on the server: maybe the user's first, so registering for messages pays. */
    data class BandCreated(val bandId: Id) : SyncEvent
}
