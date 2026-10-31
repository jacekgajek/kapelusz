package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.history.BandSettings
import com.jacekgajek.kapelusz.history.settings
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.RejectionReason

/**
 * Kinds of changes that cannot be undone: joining by invite, unlinking an account, passing on ownership, and settling
 * together and one's own payment details, which are each member's own decision (ITERATION_5 §7, §8), and deleting the
 * band or keeping it, whose times are the server's (ITERATION_7 §9); and a kind this version doesn't know (ITERATION_8 §2).
 */
fun isUndoable(op: Operation): Boolean = when (op) {
    is LinkUser, is UnlinkUser, is TransferOwnership, is SetBlikPhone, is SetBankAccount, is ScheduleBandDeletion, is CancelBandDeletion,
    is InviteToSettleTogether, is CancelSettleTogether, is AcceptSettleTogether, is DeclineSettleTogether, is StopSettlingTogether,
    is UnknownOperation,
    -> false

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
        val unchanged = settingsUnchanged(op.change.before.settings, after.settings, band.settings) &&
            // A photo is not a change of the band (the server updates it at sign-in): undo ignores it. So does it a pair
            // formed or ended since (ITERATION_5 §7), which the undone change didn't touch.
            after.members.all { (id, value) -> band.member(id)?.plain() == value?.plain() } &&
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
        val settings = restoreSettings(before.settings, after.settings, band.settings)
        val restored = band.copy(
            name = settings.name,
            treasurerMemberId = settings.treasurerMemberId,
            agendaTemplate = settings.agendaTemplate,
            fundDefault = settings.fundDefault,
            fundBlikPhone = settings.fundBlikPhone,
            fundBankAccount = settings.fundBankAccount,
            members = restore(
                band.members,
                before.members.mapValues { (id, member) ->
                    val current = band.member(id)
                    // A pair or invitation the change ended comes back (e.g. undoing an archive); one it left alone stays
                    // as it is now.
                    val later = after.members[id]
                    member?.copy(
                        pictureUrl = current?.pictureUrl ?: member.pictureUrl,
                        // Only ever set by their own operations, which are not undoable (ITERATION_5 §8, ITERATION_7 §7).
                        blikPhone = if (current == null) member.blikPhone else current.blikPhone,
                        bankAccount = if (current == null) member.bankAccount else current.bankAccount,
                        settlesWith = if (current == null || member.settlesWith != later?.settlesWith) member.settlesWith else current.settlesWith,
                        invitedToSettle = if (current == null || member.invitedToSettle != later?.invitedToSettle) member.invitedToSettle else current.invitedToSettle,
                    )
                },
                before.memberOrder,
            ) { it.id },
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

    /** References that point nowhere, over-refunded costs and duplicate member names; ordinary operations never make them. */
    fun problems(band: Band): Set<String> = buildSet {
        val memberIds = band.members.map { it.id }.toSet()
        val costs = band.costs.associateBy { it.id }
        band.treasurerMemberId?.let { id -> if (band.member(id)?.archived != false) add("treasurer $id") }
        // Names are unique (ITERATION_5 §12): e.g. a deleted member brought back after another took their name.
        band.members.filter { member -> band.members.any { it !== member && it.name.trim().equals(member.name.trim(), ignoreCase = true) } }
            .forEach { add("name ${it.name.trim().lowercase()}") }
        // A pair or invitation that no longer holds, e.g. one partner settling with someone else since (ITERATION_5 §7).
        if (SettleTogether.tidy(band) != band) add("settling together")
        // The fund is off only when it is empty and every event handed over its part (as DisableBandFund requires).
        if (band.treasurerMemberId == null && !BandFund.canDisable(band)) add("band fund in use without a treasurer")
        for (event in band.events) {
            val guestIds = event.guests.map { it.id }.toSet()
            event.participants.filter { it.partyId !in memberIds && it.partyId !in guestIds }.forEach { add("participant ${it.partyId} at ${event.id}") }
            val parties = event.participants.map { it.partyId }.toSet()
            event.holdings.filter { it.partyId !in parties && it.partyId != ORGANISER && it.partyId != BAND_FUND }.forEach { add("holding ${it.id} at ${event.id}") }
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
        band.fundEntries.forEach { entry -> entry.paidBy?.let { if (it !in memberIds) add("payer of fund entry ${entry.id}") } }
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

/**
 * Each setting the change made (from [before] to [after]) is still as it left it in [current]; settings it didn't touch
 * may have changed since, e.g. the fund's account set after a rename (ITERATION_7 §7).
 */
private fun settingsUnchanged(before: BandSettings?, after: BandSettings?, current: BandSettings): Boolean = before == null || after == null || settingFields.all { field -> field(before) == field(after) || field(current) == field(after) }

/** [current] with the settings the change made set back to [before]; the others as they are now. */
private fun restoreSettings(before: BandSettings?, after: BandSettings?, current: BandSettings): BandSettings {
    if (before == null || after == null) return before ?: current
    fun <T> pick(field: (BandSettings) -> T): T = if (field(before) != field(after)) field(before) else field(current)
    return BandSettings(
        name = pick { it.name },
        treasurerMemberId = pick { it.treasurerMemberId },
        agendaTemplate = pick { it.agendaTemplate },
        fundDefault = pick { it.fundDefault },
        fundBlikPhone = pick { it.fundBlikPhone },
        fundBankAccount = pick { it.fundBankAccount },
    )
}

private val settingFields: List<(BandSettings) -> Any?> = listOf(
    { it.name },
    { it.treasurerMemberId },
    { it.agendaTemplate },
    { it.fundDefault },
    { it.fundBlikPhone },
    { it.fundBankAccount },
)

/** A member as undo compares them: without the photo and the pair, which change on their own. */
private fun Member.plain() = copy(pictureUrl = null, settlesWith = null, invitedToSettle = null, blikPhone = null, bankAccount = null)
