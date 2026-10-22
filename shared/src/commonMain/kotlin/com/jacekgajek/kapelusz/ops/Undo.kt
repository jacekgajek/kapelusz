package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import kotlinx.serialization.Serializable

/** The band-wide settings an operation may change, as one value. */
@Serializable
data class BandSettings(val name: String, val treasurerMemberId: Id? = null, val agendaTemplate: List<AgendaTemplateItem>? = null)

/**
 * Part of a band: the entities an operation touched, by id (null: it did not exist), and the settings if they changed.
 * The orders are kept where an operation removed or moved something, so undoing it puts things back in place.
 */
@Serializable
data class BandPart(
    val settings: BandSettings? = null,
    val members: Map<Id, Member?> = emptyMap(),
    val events: Map<Id, Event?> = emptyMap(),
    val costs: Map<Id, Cost?> = emptyMap(),
    val fundEntries: Map<Id, FundEntry?> = emptyMap(),
    val memberOrder: List<Id>? = null,
    val eventOrder: List<Id>? = null,
    val costOrder: List<Id>? = null,
    val fundEntryOrder: List<Id>? = null,
)

/** What one operation changed (ITERATION_2 §7.2): the touched parts of the band before and after it. */
@Serializable
data class BandChange(val before: BandPart, val after: BandPart) {
    val isEmpty: Boolean get() = before == BandPart() && after == BandPart()

    /** Undoing would bring back attachments whose files the server deleted when they were removed. */
    val restoresAttachments: Boolean
        get() {
            val now = after.costs.values.filterNotNull().flatMap { it.attachments }.map { it.id }.toSet()
            return before.costs.values.filterNotNull().flatMap { it.attachments }.any { it.id !in now }
        }

    companion object {
        fun between(before: Band, after: Band): BandChange {
            val members = diff(before.members, after.members) { it.id }
            val events = diff(before.events, after.events) { it.id }
            val costs = diff(before.costs, after.costs) { it.id }
            val fundEntries = diff(before.fundEntries, after.fundEntries) { it.id }
            val settingsBefore = before.settings
            val settingsAfter = after.settings
            val settingsChanged = settingsBefore != settingsAfter
            return BandChange(
                BandPart(
                    settings = settingsBefore.takeIf { settingsChanged },
                    members = members.before, events = events.before, costs = costs.before, fundEntries = fundEntries.before,
                    memberOrder = members.order, eventOrder = events.order, costOrder = costs.order, fundEntryOrder = fundEntries.order,
                ),
                BandPart(
                    settings = settingsAfter.takeIf { settingsChanged },
                    members = members.after, events = events.after, costs = costs.after, fundEntries = fundEntries.after,
                    // Undoing an undo (a redo) restores these orders.
                    memberOrder = diff(after.members, before.members) { it.id }.order,
                    eventOrder = diff(after.events, before.events) { it.id }.order,
                    costOrder = diff(after.costs, before.costs) { it.id }.order,
                    fundEntryOrder = diff(after.fundEntries, before.fundEntries) { it.id }.order,
                ),
            )
        }
    }
}

/** Kinds of changes that cannot be undone: joining by invite, unlinking an account, and passing on ownership. */
fun isUndoable(op: Operation): Boolean = when (op) {
    is LinkUser, is UnlinkUser, is TransferOwnership -> false
    is Undo -> isUndoable(op.original)
    else -> true
}

internal object Undoing {
    /** Undo, redo, undo, redo: enough for any real use, and bounded so a history entry stays small to decode. */
    private const val MAX_DEPTH = 4

    private fun depth(op: Operation): Int = if (op is Undo) 1 + depth(op.original) else 0

    fun apply(band: Band, op: Undo): Band {
        invalidIf(depth(op) > MAX_DEPTH, RejectionReason.NOT_UNDOABLE) { "Too many undos of undos" }
        invalidIf(!isUndoable(op.original) || op.change.isEmpty || op.change.restoresAttachments, RejectionReason.NOT_UNDOABLE) { "This change cannot be undone" }
        val after = op.change.after
        val unchanged = (after.settings == null || after.settings == band.settings) &&
            // A photo is not a change of the band (the server updates it at sign-in): undo ignores it.
            after.members.all { (id, value) -> band.member(id)?.copy(pictureUrl = null) == value?.copy(pictureUrl = null) } &&
            after.events.all { (id, value) -> band.event(id) == value } &&
            after.costs.all { (id, value) -> band.cost(id) == value } &&
            after.fundEntries.all { (id, value) -> band.fundEntries.firstOrNull { it.id == id } == value }
        // A move has no entity to compare: the order must still be the one it left.
        val reordered = listOf(
            Triple(op.change.before.memberOrder, after.memberOrder, band.members.map { it.id }),
            Triple(op.change.before.eventOrder, after.eventOrder, band.events.map { it.id }),
            Triple(op.change.before.costOrder, after.costOrder, band.costs.map { it.id }),
            Triple(op.change.before.fundEntryOrder, after.fundEntryOrder, band.fundEntries.map { it.id }),
        ).all { (beforeOrder, afterOrder, current) -> beforeOrder == null || afterOrder == null || beforeOrder.toSet() != afterOrder.toSet() || current == afterOrder }
        conflictIf(!unchanged || !reordered, RejectionReason.CHANGED_SINCE) { "Changed since, can't undo" }

        val before = op.change.before
        val settings = before.settings ?: band.settings
        val restored = band.copy(
            name = settings.name,
            treasurerMemberId = settings.treasurerMemberId,
            agendaTemplate = settings.agendaTemplate,
            members = restore(band.members, before.members.mapValues { (id, member) -> member?.copy(pictureUrl = band.member(id)?.pictureUrl ?: member.pictureUrl) }, before.memberOrder) { it.id },
            // Exactly as before (also Event.updatedAt), so after a redo the original change can be undone again.
            events = restore(band.events, before.events, before.eventOrder) { it.id },
            costs = restore(band.costs, before.costs, before.costOrder) { it.id },
            fundEntries = restore(band.fundEntries, before.fundEntries, before.fundEntryOrder) { it.id },
        )
        // Other changes since may rely on what the undone change did (e.g. a participant added after the member was).
        val broken = problems(restored) - problems(band)
        conflictIf(broken.isNotEmpty(), RejectionReason.CHANGED_SINCE) { "Changed since, can't undo: ${broken.joinToString()}" }
        return restored
    }

    /** References that point nowhere and over-refunded costs; these never come from ordinary operations. */
    fun problems(band: Band): Set<String> = buildSet {
        val memberIds = band.members.map { it.id }.toSet()
        val costs = band.costs.associateBy { it.id }
        band.treasurerMemberId?.let { id -> if (band.member(id)?.archived != false) add("treasurer $id") }
        // The fund is off only when it is empty and every event handed over its part (as DisableBandFund requires).
        if (band.treasurerMemberId == null && !BandFund.canDisable(band)) add("band fund in use without a treasurer")
        for (event in band.events) {
            val guestIds = event.guests.map { it.id }.toSet()
            event.participants.filter { it.partyId !in memberIds && it.partyId !in guestIds }.forEach { add("participant ${it.partyId} at ${event.id}") }
            val parties = event.participants.map { it.partyId }.toSet()
            event.holdings.filter { it.partyId !in parties }.forEach { add("holding ${it.id} at ${event.id}") }
            event.transfers.filter { t -> listOf(t.from, t.to).any { it != BAND_FUND && it !in parties } }.forEach { add("transfer ${it.id} at ${event.id}") }
            event.refunds.filter { refund -> costs[refund.costId]?.let { CostLedger.isRefundableAt(it, event) } != true }
                .forEach { add("refund of ${it.costId} at ${event.id}") }
        }
        val eventIds = band.events.map { it.id }.toSet()
        for (cost in band.costs) {
            if (cost.memberId !in memberIds) add("owner of cost ${cost.id}")
            cost.eventId?.let { if (it !in eventIds) add("event of cost ${cost.id}") }
            if (CostLedger.outstanding(band, cost).isNegative) add("cost ${cost.id} refunded more than its amount")
        }
        band.fundEntries.forEach { entry -> entry.costId?.let { if (it !in costs) add("cost of fund entry ${entry.id}") } }
    }

    /**
     * [list] with [values] put back (null removes). A move is undone to [order] (checked to be unchanged since);
     * otherwise things coming back go after their old neighbour, and everything else keeps its current place.
     */
    private fun <T> restore(list: List<T>, values: Map<Id, T?>, order: List<Id>?, id: (T) -> Id): List<T> {
        val present = list.map(id).toSet()
        val result = list.mapNotNull { item -> if (id(item) in values) values[id(item)] else item }.toMutableList()
        val returning = values.filterKeys { it !in present }.values.filterNotNull()
        if (order == null) return result + returning
        if (returning.isEmpty() && result.map(id).toSet() == order.toSet()) return result.sortedBy { order.indexOf(id(it)) }
        for (item in returning.sortedBy { order.indexOf(id(it)) }) {
            val earlier = order.subList(0, order.indexOf(id(item)).coerceAtLeast(0))
            val neighbour = earlier.lastOrNull { previous -> result.any { id(it) == previous } }
            result.add(if (neighbour == null) 0 else result.indexOfFirst { id(it) == neighbour } + 1, item)
        }
        return result
    }
}

private val Band.settings get() = BandSettings(name, treasurerMemberId, agendaTemplate)

private class Diff<T>(val before: Map<Id, T?>, val after: Map<Id, T?>, val order: List<Id>?)

/**
 * The entities that differ between [before] and [after], and the order of [before] when the change removed or moved
 * something (an addition alone needs no order: undoing it just removes).
 */
private fun <T> diff(before: List<T>, after: List<T>, id: (T) -> Id): Diff<T> {
    val old = before.associateBy(id)
    val new = after.associateBy(id)
    val changed = (old.keys + new.keys).filter { old[it] != new[it] }
    val removed = old.keys.any { it !in new }
    val moved = before.map(id).filter { it in new } != after.map(id).filter { it in old }
    return Diff(changed.associateWith { old[it] }, changed.associateWith { new[it] }, before.map(id).takeIf { removed || moved })
}
