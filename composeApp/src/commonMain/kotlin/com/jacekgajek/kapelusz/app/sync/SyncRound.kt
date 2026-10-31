package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.app.attachments.AttachmentUploads
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.LocalBand
import com.jacekgajek.kapelusz.app.util.nowMillis
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.ops.Undo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException

/**
 * The sync protocol for one band at a time (REFACTORING.md 3.5): push what waits (creating the band first), upload
 * its files, pull the server's state, and keep the band list right. No loop, no lock, no status of its own: the
 * [SyncEngine] decides when and what, and shows the outcome. What a round learns that others react to comes out as
 * flows: [events], [rejections], [lostAccess] and the pushes that are [stuck].
 */
class SyncRound(
    private val api: ApiClient,
    private val bands: BandRepository,
    private val uploads: AttachmentUploads? = null,
    private val clock: () -> Long = ::nowMillis,
) {
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)

    /** What happened during a sync that others react to. */
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 16)

    /** Operations the server refused one by one during a push (e.g. a role changed meanwhile): told to the user. */
    val rejections: SharedFlow<Rejection> = _rejections.asSharedFlow()

    private val _lostAccess = MutableSharedFlow<LostBand>(extraBufferCapacity = 4)

    /** Bands this device had to let go of: deleted, or the user removed from them. */
    val lostAccess: SharedFlow<LostBand> = _lostAccess.asSharedFlow()

    private val _stuck = MutableStateFlow<Map<Id, StuckPush>>(emptyMap())

    /** Bands whose unsent changes the server refuses as a whole (ITERATION_3 R9): not pushed again until the user decides. */
    val stuck: StateFlow<Map<Id, StuckPush>> = _stuck.asStateFlow()

    /** The user discarded the refused changes: the band is pushed again from now on. */
    fun unstick(bandId: Id) = _stuck.update { it - bandId }

    /** Push, upload and (when [pull]) pull one band; what it came to, as a status. */
    @Suppress("TooGenericExceptionCaught") // on the web, browser errors are JsException, a Throwable
    suspend fun syncBand(bandId: Id, pull: Boolean): SyncStatus = try {
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

    /** A band the user belongs to that is not on this device yet: kept without opening it. */
    suspend fun fetchBand(bandId: Id) {
        api.state(bandId, null)?.let { bands.storeFromServer(it.band, it.version) }
    }

    /** What a failure means for the whole sync, as the status it shows. */
    fun classify(e: Throwable): SyncStatus = when {
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
    suspend fun checkBandList(listed: List<BandSummary>) {
        val unlisted = bands.unlisted(listed)
        bands.onBandList(listed)
        for (bandId in unlisted) syncBand(bandId, pull = true)
    }

    private suspend fun storedOrStop() {
        if (!bands.flush()) throw SyncProtocolException("This device could not store the band; not pushing until it can")
    }

    suspend fun pushBand(bandId: Id) {
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

    suspend fun pullBand(bandId: Id) {
        val local = bands.local(bandId) ?: return
        if (local.needsCreate) return
        val response = api.state(bandId, local.version) ?: return
        bands.onPulled(bandId, response.band, response.version)
    }

    companion object {
        const val BATCH_SIZE = 200

        /**
         * The next ops to push: at most [BATCH_SIZE], and no more undos than the server takes at once (ITERATION_3 R6);
         * none from the first one still [held][LocalBand.holds] on, as later ones may build on it (ITERATION_5 §2).
         */
        internal fun batchOf(pending: List<OpEnvelope>, holds: Map<Id, Long> = emptyMap(), now: Long = 0): List<OpEnvelope> {
            var undos = 0
            val notHeld = pending.takeWhile { (holds[it.opId] ?: 0) <= now }.take(BATCH_SIZE)
            return notHeld.takeWhile { envelope -> envelope.op !is Undo || ++undos <= Limits.MAX_UNDOS_PER_PUSH }
        }
    }
}
