package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UnknownOperation
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.view.BandView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    /**
     * The data format [base] was stored with. An older app drops fields it does not know (e.g. agenda times) but
     * keeps the server's [version], so the server would keep answering "not modified"; a band stored with an older
     * format is therefore downloaded in full once. Missing (0) for bands stored before this field existed.
     */
    val schema: Int = 0,
    /**
     * Operations held back from syncing while their Undo snackbar shows (ITERATION_5 §2): op id → until when (epoch
     * ms). Nothing from the first held one on is sent; past its time a hold no longer counts (e.g. the app was closed).
     */
    val holds: Map<Id, Long> = emptyMap(),
)

/** The signed-in user's bands on this device and which one is open. */
@Serializable
data class BandIndex(val userId: String, val currentBandId: Id? = null, val bands: List<BandSummary> = emptyList())

sealed interface BandState {
    data object Loading : BandState
    data object NoBand : BandState

    /** Saved data could not be read; it was copied to [backupKey] before anything else happened. */
    data class Unreadable(val backupKey: String) : BandState

    /** [band] is what the UI shows: [LocalBand.base] with all pending operations applied; [view] derives from it once. */
    data class Ready(val view: BandView, val userId: String) : BandState {
        constructor(band: Band, userId: String) : this(BandView(band), userId)

        val band: Band get() = view.band
        val me: Member? get() = band.memberOfUser(userId)
    }
}

/**
 * Offline-first band state for the signed-in user. Every change is an [Operation] applied immediately with the shared
 * [Engine] (the same permission checks and reducer the server runs), queued and written through [BandStore]; [com.jacekgajek.kapelusz.app.sync.SyncEngine]
 * later pushes the queue and merges the server's state.
 */
class BandRepository(
    private val store: BandStore,
    private val idGenerator: () -> String = ::newId,
    private val clock: () -> Long = ::nowMillis,
) {
    private val _state = MutableStateFlow<BandState>(BandState.Loading)
    val state: StateFlow<BandState> = _state.asStateFlow()

    private val _index = MutableStateFlow<BandIndex?>(null)
    val index: StateFlow<BandIndex?> = _index.asStateFlow()

    private val locals = mutableMapOf<Id, LocalBand>()
    private var userId: String? = null

    /** Its own store over a plain [KeyValueStore], writing in [scope]: tests and tools. */
    constructor(store: KeyValueStore, scope: CoroutineScope, idGenerator: () -> String = ::newId, clock: () -> Long = ::nowMillis) :
        this(BandStore(store, scope, clock), idGenerator, clock)

    init {
        store.bind(object : BandStore.Source {
            override val userId get() = this@BandRepository.userId
            override val index get() = _index.value
            override fun local(bandId: Id) = locals[bandId]
        })
    }

    val currentBandId: Id? get() = _index.value?.currentBandId

    /**
     * Loads all of the user's bands stored on this device and opens the last used one. Storage keys include the user
     * id, so another account signed in on the same device never sees (or pushes) these bands.
     */
    suspend fun load(userId: String) {
        this.userId = userId
        locals.clear()
        store.forgetUnwritten()
        val rawIndex = store.readIndex(userId)
        val index = rawIndex?.let { raw -> runCatching { KapeluszJson.decodeFromString(BandIndex.serializer(), raw) }.getOrNull() }
            ?: BandIndex(userId).also {
                // Never lose the only copy (ITERATION_3 R4).
                if (rawIndex != null) store.backupIndex(userId, rawIndex)
            }
        _index.value = index
        // Unreadable: the bands it listed are found again from their own files, also those created offline that the
        // server does not know yet. The ids are picked out of the broken text.
        if (rawIndex != null && index.bands.isEmpty()) recoverBands(userId, rawIndex)
        // Every stored band, not only the open one: bands created offline must still be uploaded, and unsent changes
        // in other bands pushed, even if the user never opens them.
        for (summary in _index.value?.bands.orEmpty()) {
            if (summary.id == _index.value?.currentBandId) continue
            runSuspendCatching { readLocal(summary.id, reportUnreadable = false) }.getOrNull()?.let { locals[summary.id] = it }
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
    suspend fun hasStored(bandId: Id): Boolean = bandId in locals || store.hasBand(requireUser(), bandId)

    /** Creates a band on this device (the user is its owner); the next sync creates it on the server. */
    fun createBand(name: String, currency: String, yourName: String) {
        val owner = requireNotNull(userId) { "Not signed in" }
        val band = Band.create(idGenerator(), name.trim(), currency, idGenerator(), yourName.trim(), owner)
        val local = LocalBand(base = band, clientId = idGenerator(), userId = owner, needsCreate = true, schema = CURRENT_SCHEMA)
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
        val local = LocalBand(base = band, version = version, clientId = idGenerator(), userId = owner, schema = CURRENT_SCHEMA)
        locals[band.id] = local
        persist(band.id)
        val me = band.memberOfUser(owner)
        updateIndex { index ->
            val summary = BandSummary(band.id, band.name, me?.name.orEmpty(), me?.role ?: Role.MEMBER)
            index.copy(currentBandId = band.id, bands = index.bands.filterNot { it.id == band.id } + summary)
        }
        _state.value = BandState.Ready(band, owner)
    }

    /**
     * Keeps a band the user belongs to on this device without opening it (syncing every band, e.g. for an event link);
     * an open band is left to the regular sync.
     */
    suspend fun storeFromServer(band: Band, version: String) {
        val owner = userId ?: return
        if (band.id in locals) return
        // Already on the device (e.g. missing from an unreadable index): its unsent changes must not be overwritten
        // (ITERATION_3 R4); the regular sync rebases them on the server's state.
        val stored = runSuspendCatching { readLocal(band.id, reportUnreadable = false) }.getOrNull()
        locals[band.id] = stored ?: LocalBand(base = band, version = version, clientId = idGenerator(), userId = owner, schema = CURRENT_SCHEMA)
        if (stored == null) persist(band.id)
    }

    /** Every band on this device as the user sees it (with unsent changes). */
    fun allBands(): List<Band> = locals.values.map(::replay)

    /** The id of the operation [dispatch] applied last, e.g. to recognise it in the history later. */
    var lastOpId: Id? = null
        private set

    /**
     * Applies [op] locally. Returns the rejection if the operation is not allowed or not valid. With [holdFor] (ms),
     * it is not synced until [release]d or that time has passed, and until then [withdraw] can take it back.
     */
    fun dispatch(op: Operation, holdFor: Long? = null): Rejection? {
        val bandId = currentBandId ?: error("No band open")
        val current = locals.getValue(bandId)
        val shown = (_state.value as BandState.Ready).band
        val envelope = OpEnvelope(idGenerator(), current.clientId, current.nextSeq, clock(), op)
        return when (val result = Engine.apply(shown, Actor.User(current.userId), op, envelope.opId, envelope.timestamp)) {
            is ApplyResult.Rejected -> result.rejection

            is ApplyResult.Applied -> {
                val holds = holdFor?.let { current.holds + (envelope.opId to clock() + it) } ?: current.holds
                locals[bandId] = current.copy(pending = current.pending + envelope, nextSeq = current.nextSeq + 1, holds = holds)
                lastOpId = envelope.opId
                _state.value = BandState.Ready(result.band, current.userId)
                persist(bandId)
                null
            }
        }
    }

    /** Lets the held operations [opIds] sync; also called for what is about to be sent, so it is never withdrawn. */
    fun release(bandId: Id, vararg opIds: Id) {
        val local = locals[bandId] ?: return
        if (opIds.none { it in local.holds }) return
        locals[bandId] = local.copy(holds = local.holds - opIds.toSet())
        persist(bandId)
    }

    /**
     * Undo of the held operation [opId]: dropped as if it was never made. False when it is not held any more (its
     * time passed, so it may be on its way to the server): then only the history can undo it.
     */
    fun withdraw(bandId: Id, opId: Id): Boolean {
        val until = locals[bandId]?.holds?.get(opId) ?: return false
        if (until <= clock()) return false
        rebase(bandId) { local -> local.copy(pending = local.pending.filterNot { it.opId == opId }, holds = local.holds - opId) }
        return true
    }

    // ---- sync hooks ----

    fun local(bandId: Id): LocalBand? = locals[bandId]

    /** Bands on this device with something to send. */
    fun bandsToPush(): List<Id> = locals.filterValues { it.needsCreate || it.pending.isNotEmpty() }.keys.toList()

    fun onCreated(bandId: Id, band: Band, version: String) = rebase(bandId) { it.copy(base = band, version = version, needsCreate = false) }

    /** The server answered a push: take its state and drop every op it accepted, rejected or already had. */
    fun onPushed(bandId: Id, response: PushResponse) {
        val done = (response.accepted + response.rejected.keys + response.duplicates).toSet()
        rebase(bandId) { local ->
            val pending = local.pending.filterNot { it.opId in done }
            local.copy(base = response.band, version = response.version, pending = pending, holds = local.holds.filterKeys { id -> pending.any { it.opId == id } })
        }
    }

    fun onPulled(bandId: Id, band: Band, version: String) = rebase(bandId) { it.copy(base = band, version = version) }

    /** Bands on this device that the server no longer lists (not ones created offline): access lost, or deleted. */
    fun unlisted(bands: List<BandSummary>): List<Id> {
        val listed = bands.map { it.id }.toSet()
        return locals.filter { (id, local) -> id !in listed && !local.needsCreate }.keys.toList()
    }

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
        if (local != null) store.keepUnsent(requireUser(), bandId, local)
        store.remove(requireUser(), bandId)
        val wasCurrent = currentBandId == bandId
        updateIndex { index -> index.copy(currentBandId = index.currentBandId.takeIf { it != bandId }, bands = index.bands.filterNot { it.id == bandId }) }
        if (wasCurrent) _state.value = BandState.NoBand
        return name
    }

    /**
     * Drops the band's unsent changes, e.g. ones the server always refuses (ITERATION_3 R9); they are kept under a
     * backup key first, as when access is lost. Sequence numbers go on from where they were. Returns the dropped
     * operations: only those in the backup, not ones made while it was being written.
     */
    suspend fun discardPending(bandId: Id): List<OpEnvelope> {
        val local = locals[bandId] ?: return emptyList()
        store.keepUnsent(requireUser(), bandId, local)
        val backedUp = local.pending.map { it.opId }.toSet()
        rebase(bandId) { band -> band.copy(pending = band.pending.filterNot { it.opId in backedUp }, holds = band.holds - backedUp) }
        return local.pending
    }

    /** The band's unsent changes as readable JSON, for the user to keep (ITERATION_3 R9). */
    fun pendingAsJson(bandId: Id): String = PrettyJson.encodeToString(ListSerializer(OpEnvelope.serializer()), locals[bandId]?.pending.orEmpty())

    /** Forgets a band deleted on purpose (nothing is kept) and opens another of the user's bands, if any. */
    suspend fun forgetDeleted(bandId: Id) {
        locals.remove(bandId)
        store.remove(requireUser(), bandId)
        updateIndex { index -> index.copy(currentBandId = index.currentBandId.takeIf { it != bandId }, bands = index.bands.filterNot { it.id == bandId }) }
        open(_index.value?.bands?.firstOrNull { hasStored(it.id) }?.id)
    }

    /** After [BandState.Unreadable]: drops the unreadable band (its backup stays). */
    suspend fun startOver() {
        val bandId = currentBandId
        if (bandId != null) store.remove(requireUser(), bandId)
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
        val raw = store.readBand(requireUser(), bandId) ?: return null
        return try {
            KapeluszJson.decodeFromString(LocalBand.serializer(), raw).let(::withoutUnknown).also { replay(it) }.let { local ->
                if (local.schema >= CURRENT_SCHEMA) {
                    local
                } // Forget the version: the next pull gets the full state instead of "not modified".
                else {
                    local.copy(version = null, schema = CURRENT_SCHEMA).also { store.markDirty(bandId, flushSoon = false) }
                }
            }
        } catch (e: Exception) {
            // Never lose the only copy: keep the unreadable data as a backup before offering to start over.
            val backupKey = store.backupBand(requireUser(), bandId, raw)
            if (reportUnreadable) {
                updateIndex { it.copy(currentBandId = bandId) }
                _state.value = BandState.Unreadable(backupKey)
            }
            throw UnreadableBand()
        }
    }

    /**
     * Unsent operations of a kind this version doesn't know (ITERATION_8 §2), e.g. a `MoveMember` from before it was
     * removed, are dropped: the server would refuse them anyway.
     */
    private fun withoutUnknown(local: LocalBand): LocalBand {
        val unknown = local.pending.filter { it.op is UnknownOperation }.map { it.opId }.toSet()
        if (unknown.isEmpty()) return local
        store.markDirty(local.base.id, flushSoon = false)
        return local.copy(pending = local.pending.filterNot { it.opId in unknown }, holds = local.holds - unknown)
    }

    private suspend fun recoverBands(userId: String, rawIndex: String) {
        val ids = Regex(""""id"\s*:\s*"([A-Za-z0-9_-]{1,64})"""").findAll(rawIndex).map { it.groupValues[1] }.distinct().toList()
        val summaries = ids.mapNotNull { id ->
            val local = runSuspendCatching { readLocal(id, reportUnreadable = false) }.getOrNull() ?: return@mapNotNull null
            locals[id] = local
            val me = local.base.memberOfUser(userId)
            BandSummary(id, local.base.name, me?.name.orEmpty(), me?.role ?: Role.MEMBER)
        }
        if (summaries.isNotEmpty()) updateIndex { it.copy(bands = summaries, currentBandId = summaries.first().id) }
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
        store.markIndexDirty()
    }

    private fun persist(bandId: Id) = store.markDirty(bandId)

    /** Writes every changed band (and the index) now: see [BandStore.flush]. */
    suspend fun flush(): Boolean = store.flush()

    private fun requireUser(): String = requireNotNull(userId) { "Not signed in" }

    private class UnreadableBand : RuntimeException()

    companion object {
        /** Raise when the band model gains fields an older app would drop (V2-M3: agenda times). */
        const val CURRENT_SCHEMA = 3

        private val PrettyJson = Json(from = KapeluszJson) { prettyPrint = true }
    }
}
