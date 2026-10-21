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
    private val dirty = mutableSetOf<Id>()
    private var indexDirty = false
    private var userId: String? = null
    private val writeLock = Mutex()

    val currentBandId: Id? get() = _index.value?.currentBandId

    /**
     * Loads all of the user's bands stored on this device and opens the last used one. Storage keys include the user
     * id, so another account signed in on the same device never sees (or pushes) these bands. Also adopts data saved
     * by older versions.
     */
    suspend fun load(userId: String) {
        this.userId = userId
        locals.clear()
        dirty.clear()
        migrateUnscopedKeys(userId)
        val index = store.read(indexKey(userId))
            ?.let { raw -> runCatching { ArtSplitJson.decodeFromString(BandIndex.serializer(), raw) }.getOrNull() }
            ?: BandIndex(userId)
        _index.value = index
        adoptLegacyBand(userId)
        // Every stored band, not only the open one: bands created offline must still be uploaded, and unsent changes
        // in other bands pushed, even if the user never opens them.
        for (summary in _index.value?.bands.orEmpty()) {
            if (summary.id == _index.value?.currentBandId) continue
            runCatching { readLocal(summary.id, reportUnreadable = false) }.getOrNull()?.let { locals[summary.id] = it }
        }
        open(_index.value?.currentBandId)
    }

    /** Opens a band stored on this device; shows "no band" for `null` or a band without local data. */
    suspend fun open(bandId: Id?) {
        if (bandId == null) {
            _state.value = BandState.NoBand
            return
        }
        val local = locals[bandId] ?: try {
            readLocal(bandId, reportUnreadable = true)
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
    suspend fun hasStored(bandId: Id): Boolean = bandId in locals || store.read(bandKey(requireUser(), bandId)) != null

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

    /**
     * The server no longer lets the user see this band. Unsent changes are kept under a backup key, then the band is
     * removed from this device. Returns the band's name for the message to the user.
     */
    suspend fun loseAccess(bandId: Id): String {
        val local = locals.remove(bandId)
        val name = local?.let { replay(it).name } ?: _index.value?.bands?.firstOrNull { it.id == bandId }?.name ?: bandId
        if (local != null && local.pending.isNotEmpty()) {
            store.write("${bandKey(requireUser(), bandId)}-unsent-${clock()}", ArtSplitJson.encodeToString(LocalBand.serializer(), local))
        }
        store.remove(bandKey(requireUser(), bandId))
        val wasCurrent = currentBandId == bandId
        updateIndex { index -> index.copy(currentBandId = index.currentBandId.takeIf { it != bandId }, bands = index.bands.filterNot { it.id == bandId }) }
        if (wasCurrent) _state.value = BandState.NoBand
        return name
    }

    /** After [BandState.Unreadable]: drops the unreadable band (its backup stays). */
    suspend fun startOver() {
        val bandId = currentBandId
        if (bandId != null) store.remove(bandKey(requireUser(), bandId))
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

    private suspend fun readLocal(bandId: Id, reportUnreadable: Boolean): LocalBand? {
        val key = bandKey(requireUser(), bandId)
        val raw = store.read(key) ?: return null
        return try {
            ArtSplitJson.decodeFromString(LocalBand.serializer(), raw).also { replay(it) }
        } catch (e: Exception) {
            // Never lose the only copy: keep the unreadable data as a backup before offering to start over.
            val backupKey = "$key-backup-${clock()}"
            store.write(backupKey, raw)
            if (reportUnreadable) {
                updateIndex { it.copy(currentBandId = bandId) }
                _state.value = BandState.Unreadable(backupKey)
            }
            throw UnreadableBand()
        }
    }

    /** M3 builds stored `band-index` and `band-{id}` without the user id; move them if they belong to [userId]. */
    private suspend fun migrateUnscopedKeys(userId: String) {
        val raw = store.read(UNSCOPED_INDEX_KEY) ?: return
        val index = runCatching { ArtSplitJson.decodeFromString(BandIndex.serializer(), raw) }.getOrNull()
        if (index == null || index.userId != userId) return
        for (summary in index.bands) {
            val band = store.read("band-${summary.id}") ?: continue
            val owner = runCatching { ArtSplitJson.decodeFromString(LocalBand.serializer(), band).userId }.getOrNull()
            if (owner == userId) {
                store.write(bandKey(userId, summary.id), band)
                store.remove("band-${summary.id}")
            }
        }
        if (store.read(indexKey(userId)) == null) store.write(indexKey(userId), raw)
        store.remove(UNSCOPED_INDEX_KEY)
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
        store.write(bandKey(userId, adopted.base.id), ArtSplitJson.encodeToString(LocalBand.serializer(), adopted))
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
        indexDirty = true
        scope.launch { flush() }
    }

    private fun persist(bandId: Id) {
        dirty += bandId
        scope.launch { flush() }
    }

    /**
     * Writes every changed band (and the index) now. Sync calls this before pushing, so a crash right after a push
     * cannot leave an older `nextSeq` on disk and make the next operation reuse a sequence number.
     */
    suspend fun flush() = writeLock.withLock {
        val user = userId ?: return@withLock
        for (bandId in dirty.toList()) {
            dirty -= bandId
            locals[bandId]?.let { store.write(bandKey(user, bandId), ArtSplitJson.encodeToString(LocalBand.serializer(), it)) }
        }
        if (indexDirty) {
            indexDirty = false
            _index.value?.let { store.write(indexKey(user), ArtSplitJson.encodeToString(BandIndex.serializer(), it)) }
        }
    }

    private fun requireUser(): String = requireNotNull(userId) { "Not signed in" }

    private class UnreadableBand : RuntimeException()

    companion object {
        /** Before multi-band support (M2) the only band lived under this key. */
        const val LEGACY_KEY = "band"

        /** M3 stored the index without the user id. */
        const val UNSCOPED_INDEX_KEY = "band-index"

        /** Identity of the device user in M2, before Google sign-in. */
        const val LOCAL_USER_ID = "local-user"

        fun indexKey(userId: String) = "band-index-$userId"
        fun bandKey(userId: String, bandId: Id) = "band-$userId-$bandId"
    }
}
