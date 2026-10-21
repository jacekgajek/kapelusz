package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.Rejection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * What a device keeps for a band: the last state known from the server ([base]) and the operations applied locally
 * but not yet pushed ([pending]). Until sync exists (M3) every operation stays pending.
 */
@Serializable
data class LocalBand(
    val base: Band,
    val pending: List<OpEnvelope> = emptyList(),
    val clientId: String,
    val nextSeq: Long = 1,
    val userId: String,
)

sealed interface BandState {
    data object Loading : BandState
    data object NoBand : BandState

    /** [band] is what the UI shows: [LocalBand.base] with all pending operations applied. */
    data class Ready(val band: Band, val userId: String) : BandState {
        val me: Member? get() = band.memberOfUser(userId)
    }
}

/**
 * Offline-first band state. Every change is an [Operation] applied immediately with the shared [Engine] (the same
 * permission checks and reducer the server runs), appended to the pending queue and persisted.
 */
class BandRepository(
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val idGenerator: () -> String = ::newId,
    private val clock: () -> Long = ::nowMillis,
) {
    private val _state = MutableStateFlow<BandState>(BandState.Loading)
    val state: StateFlow<BandState> = _state.asStateFlow()

    private var local: LocalBand? = null
    private val writeLock = Mutex()

    suspend fun load() {
        val loaded = store.read(KEY)?.let { ArtSplitJson.decodeFromString(LocalBand.serializer(), it) }
        local = loaded
        _state.value = loaded?.let { BandState.Ready(replay(it), it.userId) } ?: BandState.NoBand
    }

    /** Creates a band on this device; the local user becomes its owner. */
    fun createBand(name: String, currency: String, yourName: String) {
        val userId = LOCAL_USER_ID
        val band = Band.create(idGenerator(), name.trim(), currency, idGenerator(), yourName.trim(), userId)
        val created = LocalBand(base = band, clientId = idGenerator(), userId = userId)
        local = created
        _state.value = BandState.Ready(band, userId)
        persist()
    }

    /** Applies [op] locally. Returns the rejection if the operation is not allowed or not valid. */
    fun dispatch(op: Operation): Rejection? {
        val current = local ?: error("No band loaded")
        val shown = (_state.value as BandState.Ready).band
        val envelope = OpEnvelope(idGenerator(), current.clientId, current.nextSeq, clock(), op)
        return when (val result = Engine.apply(shown, Actor.User(current.userId), op, envelope.opId, envelope.timestamp)) {
            is ApplyResult.Rejected -> result.rejection
            is ApplyResult.Applied -> {
                local = current.copy(pending = current.pending + envelope, nextSeq = current.nextSeq + 1)
                _state.value = BandState.Ready(result.band, current.userId)
                persist()
                null
            }
        }
    }

    private fun replay(local: LocalBand): Band = local.pending.fold(local.base) { band, envelope ->
        when (val result = Engine.apply(band, Actor.User(local.userId), envelope.op, envelope.opId, envelope.timestamp)) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> band
        }
    }

    private fun persist() {
        scope.launch {
            writeLock.withLock {
                local?.let { store.write(KEY, ArtSplitJson.encodeToString(LocalBand.serializer(), it)) }
            }
        }
    }

    companion object {
        const val KEY = "band"

        /** Identity of the device user until Google sign-in arrives in M3. */
        const val LOCAL_USER_ID = "local-user"
    }
}
