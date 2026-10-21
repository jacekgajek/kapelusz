package com.jacekgajek.artsplit.app.sync

import com.jacekgajek.artsplit.api.CreateBandRequest
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

enum class SyncStatus { IDLE, SYNCING, SYNCED, OFFLINE, NEEDS_SIGN_IN, ERROR }

/**
 * Keeps the device and the server in step (REFINED_PLAN §5.2): creates bands made offline, pushes queued operations,
 * pulls the current band, and refreshes the band list. Runs on demand (after a change), every [interval] while the
 * app is in the foreground, and retries with backoff while offline.
 */
class SyncEngine(
    private val api: ApiClient,
    private val bands: BandRepository,
    private val scope: CoroutineScope,
    private val interval: Long = 30_000,
) {
    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 16)

    /** Operations the server refused (e.g. permissions changed meanwhile); shown to the user. */
    val rejections: SharedFlow<Rejection> = _rejections.asSharedFlow()

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

    /** Asks for a sync soon, e.g. after a local change. */
    fun requestSync() {
        wakeUp.trySend(Unit)
    }

    /** One full round; returns false when it should be retried later. */
    suspend fun syncNow(): Boolean = runLock.withLock {
        _status.value = SyncStatus.SYNCING
        val result = try {
            bands.bandsToPush().forEach { pushBand(it) }
            bands.currentBandId?.let { pullBand(it) }
            bands.onBandList(api.me().bands)
            SyncStatus.SYNCED
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignInException) {
            SyncStatus.NEEDS_SIGN_IN
        } catch (e: ApiException) {
            SyncStatus.ERROR
        } catch (e: Exception) {
            SyncStatus.OFFLINE
        }
        _status.value = result
        result == SyncStatus.SYNCED
    }

    /** Downloads a band the user belongs to but that is not on this device yet, and opens it. */
    suspend fun download(bandId: Id) {
        val response = api.state(bandId, null) ?: return
        bands.adoptFromServer(response.band, response.version)
    }

    private suspend fun pushBand(bandId: Id) {
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
            check(batch.any { it.opId in handled }) { "Server did not process the pushed operations" }
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
