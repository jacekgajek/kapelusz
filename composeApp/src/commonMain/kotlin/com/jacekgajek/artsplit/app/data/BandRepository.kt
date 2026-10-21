package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.api.BandSummary
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.model.Role
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
 * What a device keeps for one band: the last state known from the server ([base], [version]) and the operations
 * applied locally but not yet pushed ([pending]). [needsCreate] marks a band created offline that the server does not
 * know yet.
 */
@Serializable
data class LocalBand(
    val base: Band,
    val version: String? = null,
    val pending: List<OpEnvelope> = emptyList(),
    val clientId: String,
    val nextSeq: Long = 1,
    val userId: String,
    val needsCreate: Boolean = false,
)

/** The signed-in user's bands on this device and which one is open. */
@Serializable
data class BandIndex(val userId: String, val currentBandId: Id? = null, val bands: List<BandSummary> = emptyList())

sealed interface BandState {
    data object Loading : BandState
    data object NoBand : BandState

    /** Saved data could not be read; it was copied to [backupKey] before anything else happened. */
    data class Unreadable(val backupKey: String) : BandState

    /** [band] is what the UI shows: [LocalBand.base] with all pending operations applied. */
    data class Ready(val band: Band, val userId: String) : BandState {
        val me: Member? get() = band.memberOfUser(userId)
    }
}

/**
 * Offline-first band state for the signed-in user. Every change is an [Operation] applied immediately with the shared
 * [Engine] (the same permission checks and reducer the server runs), queued and persisted; [com.jacekgajek.artsplit.app.sync.SyncEngine]
 * later pushes the queue and merges the server's state.
 */
class BandRepository(
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val idGenerator: () -> String = ::newId,
    private val clock: () -> Long = ::nowMillis,
) {
    private val _state = MutableStateFlow<BandState>(BandState.Loading)
    val state: StateFlow<BandState> = _state.asStateFlow()

    private val _index = MutableStateFlow<BandIndex?>(null)
    val index: StateFlow<BandIndex?> = _index.asStateFlow()

    private val locals = mutableMapOf<Id, LocalBand>()
    private var userId: String? = null
    private val writeLock = Mutex()

    val currentBandId: Id? get() = _index.value?.currentBandId

    /** Loads the user's bands and opens the last used one. Also adopts a band created before sign-in existed (M2). */
    suspend fun load(userId: String) {
        this.userId = userId
        locals.clear()
        val index = store.read(INDEX_KEY)
            ?.let { raw -> runCatching { ArtSplitJson.decodeFromString(BandIndex.serializer(), raw) }.getOrNull() }
            ?.takeIf { it.userId == userId }
            ?: BandIndex(userId)
        _index.value = index
        adoptLegacyBand(userId)
        open(_index.value?.currentBandId)
    }

    /** Opens a band stored on this device; shows "no band" for `null` or a band without local data. */
    suspend fun open(bandId: Id?) {
        if (bandId == null) {
            _state.value = BandState.NoBand
            return
        }
        val local = locals[bandId] ?: try {
            readLocal(bandId)
        } catch (e: UnreadableBand) {
            return
        } ?: run {
            _state.value = BandState.NoBand
            return
        }
        locals[bandId] = local
        updateIndex { it.copy(currentBandId = bandId) }
        _state.value = BandState.Ready(replay(local), local.userId)
    }

    /** Whether the band is on this device (in memory or saved), i.e. can be opened without downloading. */
    suspend fun hasStored(bandId: Id): Boolean = bandId in locals || store.read(bandKey(bandId)) != null

    /** Creates a band on this device (the user is its owner); the next sync creates it on the server. */
    fun createBand(name: String, currency: String, yourName: String) {
        val owner = requireNotNull(userId) { "Not signed in" }
        val band = Band.create(idGenerator(), name.trim(), currency, idGenerator(), yourName.trim(), owner)
        val local = LocalBand(base = band, clientId = idGenerator(), userId = owner, needsCreate = true)
        locals[band.id] = local
        persist(band.id)
        updateIndex { index ->
            index.copy(currentBandId = band.id, bands = index.bands + BandSummary(band.id, band.name, yourName.trim(), band.members.single().role))
        }
        _state.value = BandState.Ready(band, owner)
    }

    /** Stores a band downloaded from the server and opens it. */
    fun adoptFromServer(band: Band, version: String) {
        val owner = requireNotNull(userId) { "Not signed in" }
        val local = LocalBand(base = band, version = version, clientId = idGenerator(), userId = owner)
        locals[band.id] = local
        persist(band.id)
        updateIndex { it.copy(currentBandId = band.id) }
        _state.value = BandState.Ready(band, owner)
    }

    /** Applies [op] locally. Returns the rejection if the operation is not allowed or not valid. */
    fun dispatch(op: Operation): Rejection? {
        val bandId = currentBandId ?: error("No band open")
        val current = locals.getValue(bandId)
        val shown = (_state.value as BandState.Ready).band
        val envelope = OpEnvelope(idGenerator(), current.clientId, current.nextSeq, clock(), op)
        return when (val result = Engine.apply(shown, Actor.User(current.userId), op, envelope.opId, envelope.timestamp)) {
            is ApplyResult.Rejected -> result.rejection
            is ApplyResult.Applied -> {
                locals[bandId] = current.copy(pending = current.pending + envelope, nextSeq = current.nextSeq + 1)
                _state.value = BandState.Ready(result.band, current.userId)
                persist(bandId)
                null
            }
        }
    }

    // ---- sync hooks ----

    fun local(bandId: Id): LocalBand? = locals[bandId]

    /** Bands on this device with something to send. */
    fun bandsToPush(): List<Id> = locals.filterValues { it.needsCreate || it.pending.isNotEmpty() }.keys.toList()

    fun onCreated(bandId: Id, band: Band, version: String) = rebase(bandId) { it.copy(base = band, version = version, needsCreate = false) }

    /** The server answered a push: take its state and drop every op it accepted, rejected or already had. */
    fun onPushed(bandId: Id, response: PushResponse) {
        val done = (response.accepted + response.rejected.keys + response.duplicates).toSet()
        rebase(bandId) { local -> local.copy(base = response.band, version = response.version, pending = local.pending.filterNot { it.opId in done }) }
    }

    fun onPulled(bandId: Id, band: Band, version: String) = rebase(bandId) { it.copy(base = band, version = version) }

    /** Updates the band list from the server's `/me`, keeping bands created offline that the server does not know yet. */
    fun onBandList(bands: List<BandSummary>) = updateIndex { index ->
        val offline = index.bands.filter { summary -> summary.id !in bands.map { it.id } && locals[summary.id]?.needsCreate == true }
        index.copy(bands = bands + offline)
    }

    /** After [BandState.Unreadable]: drops the unreadable band (its backup stays). */
    suspend fun startOver() {
        val bandId = currentBandId
        if (bandId != null) store.remove(bandKey(bandId))
        bandId?.let { locals.remove(it) }
        updateIndex { index -> index.copy(currentBandId = null, bands = index.bands.filterNot { it.id == bandId }) }
        _state.value = BandState.NoBand
    }

    private fun rebase(bandId: Id, change: (LocalBand) -> LocalBand) {
        val local = locals[bandId] ?: return
        val updated = change(local)
        locals[bandId] = updated
        persist(bandId)
        val shown = replay(updated)
        if (bandId == currentBandId) _state.value = BandState.Ready(shown, updated.userId)
        val name = shown.name
        updateIndex { index -> index.copy(bands = index.bands.map { if (it.id == bandId) it.copy(name = name) else it }) }
    }

    private suspend fun readLocal(bandId: Id): LocalBand? {
        val raw = store.read(bandKey(bandId)) ?: return null
        return try {
            ArtSplitJson.decodeFromString(LocalBand.serializer(), raw).also { replay(it) }
        } catch (e: Exception) {
            // Never lose the only copy: keep the unreadable data as a backup before offering to start over.
            val backupKey = "${bandKey(bandId)}-backup-${clock()}"
            store.write(backupKey, raw)
            updateIndex { it.copy(currentBandId = bandId) }
            _state.value = BandState.Unreadable(backupKey)
            throw UnreadableBand()
        }
    }

    /** M2 kept one band under `band`, owned by a placeholder user; it now belongs to the signed-in user. */
    private suspend fun adoptLegacyBand(userId: String) {
        val raw = store.read(LEGACY_KEY) ?: return
        val legacy = runCatching { ArtSplitJson.decodeFromString(LocalBand.serializer(), raw) }.getOrNull()
        if (legacy == null) {
            store.write("$LEGACY_KEY-backup-${clock()}", raw)
            store.remove(LEGACY_KEY)
            return
        }
        val reassign = { band: Band -> band.copy(members = band.members.map { if (it.userId == legacy.userId) it.copy(userId = userId) else it }) }
        val adopted = legacy.copy(base = reassign(legacy.base), userId = userId, needsCreate = legacy.version == null)
        locals[adopted.base.id] = adopted
        store.write(bandKey(adopted.base.id), ArtSplitJson.encodeToString(LocalBand.serializer(), adopted))
        store.remove(LEGACY_KEY)
        val shown = replay(adopted)
        val me = shown.memberOfUser(userId)
        updateIndex { index ->
            index.copy(
                currentBandId = adopted.base.id,
                bands = index.bands.filterNot { it.id == shown.id } + BandSummary(shown.id, shown.name, me?.name.orEmpty(), me?.role ?: Role.OWNER),
            )
        }
    }

    private fun replay(local: LocalBand): Band = local.pending.fold(local.base) { band, envelope ->
        when (val result = Engine.apply(band, Actor.User(local.userId), envelope.op, envelope.opId, envelope.timestamp)) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> band
        }
    }

    private fun updateIndex(change: (BandIndex) -> BandIndex) {
        val current = _index.value ?: return
        val updated = change(current)
        if (updated == current) return
        _index.value = updated
        scope.launch { writeLock.withLock { _index.value?.let { store.write(INDEX_KEY, ArtSplitJson.encodeToString(BandIndex.serializer(), it)) } } }
    }

    private fun persist(bandId: Id) {
        scope.launch {
            writeLock.withLock {
                locals[bandId]?.let { store.write(bandKey(bandId), ArtSplitJson.encodeToString(LocalBand.serializer(), it)) }
            }
        }
    }

    private class UnreadableBand : RuntimeException()

    companion object {
        /** Before multi-band support (M2) the only band lived under this key. */
        const val LEGACY_KEY = "band"
        const val INDEX_KEY = "band-index"

        /** Identity of the device user in M2, before Google sign-in. */
        const val LOCAL_USER_ID = "local-user"

        fun bandKey(bandId: Id) = "band-$bandId"
    }
}
