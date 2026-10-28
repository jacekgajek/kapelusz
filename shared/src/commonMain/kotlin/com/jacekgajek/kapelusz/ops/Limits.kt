package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.allAttachments

/**
 * Limits on everything that comes into a band (ITERATION_3 R1), checked after every operation on the band it
 * produced, so client and server, and undo, enforce the same. Only what the operation changed is checked: data from
 * before the limits keeps working.
 */
object Limits {
    /** 1 000 000 000.00 in the band's currency: far above any gig, and safe from overflow in the settlement's sums. */
    const val MAX_AMOUNT = 100_000_000_000L
    const val MAX_NAME = 100
    const val MAX_TEXT = 500
    const val MAX_NOTES = 2000
    const val MAX_MEMBERS = 200
    const val MAX_EVENTS = 2000
    const val MAX_COSTS = 5000
    const val MAX_FUND_ENTRIES = 5000

    /**
     * Attachments in a band: with images of up to 3 MB and PDFs of up to 10 MB, a bounded amount of storage. 5000: a
     * band with 40 gigs a year and a few receipts each reaches 1000 in under ten years. Each
     * one is 100–250 bytes of state, so 5000 stay well inside the band's size cap.
     */
    const val MAX_BAND_ATTACHMENTS = 5000
    const val MAX_PARTIES_PER_EVENT = 200
    const val MAX_ITEMS_PER_EVENT = 500

    /** Undos in one push: each one reads history from storage (ITERATION_3 R6). Clients split their pushes to keep to it. */
    const val MAX_UNDOS_PER_PUSH = 20

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * Why [after] breaks a limit that [before] did not; null when it keeps to them. Field by field: a value from before
     * the limits (a long note, say) does not block other changes to the same event.
     */
    fun violation(before: Band, after: Band): Rejection? {
        if (after.name != before.name) text(after.name, MAX_NAME, "Band name")?.let { return it }
        count(before.members.size, after.members.size, MAX_MEMBERS, "members")?.let { return it }
        count(before.events.size, after.events.size, MAX_EVENTS, "events")?.let { return it }
        count(before.costs.size, after.costs.size, MAX_COSTS, "costs")?.let { return it }
        count(before.fundEntries.size, after.fundEntries.size, MAX_FUND_ENTRIES, "band fund entries")?.let { return it }
        // Files cost storage: a budget per band (ITERATION_3 R6), checked only when attachments change.
        if (after.costs !== before.costs || after.events !== before.events) {
            count(before.allAttachments().size, after.allAttachments().size, MAX_BAND_ATTACHMENTS, "attachments")?.let { return it }
        }
        // Untouched lists are the same object (the reducer copies only what changes): nothing to look at.
        if (after.members !== before.members) members(before, after)?.let { return it }
        if (after.events !== before.events) events(before, after)?.let { return it }
        if (after.costs !== before.costs) costs(before, after)?.let { return it }
        if (after.fundEntries !== before.fundEntries) fundEntries(before, after)?.let { return it }
        return null
    }

    private fun members(before: Band, after: Band): Rejection? {
        val old = before.members.associateBy { it.id }
        for (member in after.members) {
            val was = old[member.id]
            if (was == member) continue
            if (was == null) {
                id(member.id, "member")?.let { return it }
                // A member's id must not be a guest's: the guest's money would become theirs.
                if (after.events.any { event -> event.guest(member.id) != null }) return invalid("Id ${member.id} is a guest's")
            }
            if (was?.name != member.name) text(member.name, MAX_NAME, "Member name")?.let { return it }
            member.shareFixed?.let { fixed -> if (was?.shareFixed != fixed) amount(fixed, "Fixed share")?.let { return it } }
        }
        return null
    }

    private fun events(before: Band, after: Band): Rejection? {
        val old = before.events.associateBy { it.id }
        val memberIds by lazy { after.members.mapTo(mutableSetOf()) { it.id } }
        for (event in after.events) {
            val was = old[event.id]
            if (was == event) continue
            if (was == null) id(event.id, "event")?.let { return it }
            if (was?.name != event.name) text(event.name, MAX_NAME, "Event name")?.let { return it }
            if (was?.location != event.location) event.location?.let { text(it, MAX_TEXT, "Location")?.let { r -> return r } }
            if (was?.notes != event.notes) event.notes?.let { text(it, MAX_NOTES, "Notes")?.let { r -> return r } }
            if (was?.fundAmount != event.fundAmount) amount(event.fundAmount, "Band fund amount")?.let { return it }
            // Guests are participants too: the participant list is the one to count.
            count(was?.participants?.size ?: 0, event.participants.size, MAX_PARTIES_PER_EVENT, "participants")?.let { return it }
            if (was?.participants !== event.participants) {
                for (participant in event.participants) {
                    val fixed = participant.fixedShare ?: continue
                    if (was?.participant(participant.partyId)?.fixedShare != fixed) amount(fixed, "Fixed share")?.let { return it }
                }
            }
            if (was?.guests !== event.guests) {
                for (guest in event.guests) {
                    val g = was?.guest(guest.id)
                    if (g == guest) continue
                    if (g == null) {
                        id(guest.id, "guest")?.let { return it }
                        if (guest.id in memberIds) return invalid("Id ${guest.id} is a member's")
                    }
                    if (g?.name != guest.name) text(guest.name, MAX_NAME, "Guest name")?.let { return it }
                }
            }
            count(was?.holdings?.size ?: 0, event.holdings.size, MAX_ITEMS_PER_EVENT, "collected amounts")?.let { return it }
            count(was?.transfers?.size ?: 0, event.transfers.size, MAX_ITEMS_PER_EVENT, "transfers")?.let { return it }
            if (was?.holdings !== event.holdings) {
                val oldHoldings = was?.holdings.orEmpty().associateBy { it.id }
                for (holding in event.holdings) {
                    val h = oldHoldings[holding.id]
                    if (h == holding) continue
                    if (h == null) id(holding.id, "collected amount")?.let { return it }
                    if (h?.amount != holding.amount) amount(holding.amount, "Collected amount")?.let { return it }
                    if (h?.note != holding.note) holding.note?.let { text(it, MAX_TEXT, "Note")?.let { r -> return r } }
                }
            }
            if (was?.transfers !== event.transfers) {
                val oldTransfers = was?.transfers.orEmpty().associateBy { it.id }
                for (transfer in event.transfers) {
                    val t = oldTransfers[transfer.id]
                    if (t == transfer) continue
                    // Settle all names its transfers after the operation ("<opId>-<n>"), so they are ids as well.
                    if (t == null) id(transfer.id, "transfer")?.let { return it }
                    if (t?.amount != transfer.amount) amount(transfer.amount, "Transfer")?.let { return it }
                    if (t?.note != transfer.note) transfer.note?.let { text(it, MAX_TEXT, "Note")?.let { r -> return r } }
                }
            }
            if (was?.refunds !== event.refunds) {
                val oldRefunds = was?.refunds.orEmpty().associateBy { it.costId }
                for (refund in event.refunds) if (oldRefunds[refund.costId] != refund) amount(refund.amount, "Refund")?.let { return it }
            }
        }
        return null
    }

    private fun costs(before: Band, after: Band): Rejection? {
        val old = before.costs.associateBy { it.id }
        for (cost in after.costs) {
            val was = old[cost.id]
            if (was == cost) continue
            if (was == null) id(cost.id, "cost")?.let { return it }
            if (was?.amount != cost.amount) amount(cost.amount, "Cost")?.let { return it }
            if (was?.description != cost.description) cost.description?.let { text(it, MAX_TEXT, "Description")?.let { r -> return r } }
        }
        return null
    }

    private fun fundEntries(before: Band, after: Band): Rejection? {
        val old = before.fundEntries.associateBy { it.id }
        for (entry in after.fundEntries) {
            val was = old[entry.id]
            if (was == entry) continue
            if (was == null) id(entry.id, "band fund entry")?.let { return it }
            if (was?.description != entry.description) text(entry.description, MAX_TEXT, "Description")?.let { return it }
            if (was?.amount != entry.amount) amount(entry.amount, "Band fund entry")?.let { return it }
        }
        return null
    }

    private fun id(value: Id, what: String): Rejection? = when {
        value == BAND_FUND -> invalid("\"$BAND_FUND\" is reserved for the band fund, not a $what")
        value == ORGANISER -> invalid("\"$ORGANISER\" is reserved for awaited fees, not a $what")
        !ID.matches(value) -> invalid("Invalid $what id")
        else -> null
    }

    private fun text(value: String, max: Int, what: String): Rejection? =
        if (value.length > max) invalid("$what is longer than $max characters") else null

    /** Either sign: fund amounts and entries can be negative. */
    private fun amount(value: Money, what: String): Rejection? =
        if (value.minor > MAX_AMOUNT || value.minor < -MAX_AMOUNT) Rejection(RejectionCode.INVALID, "$what is too large", RejectionReason.INVALID_AMOUNT) else null

    /** Only growth past a limit counts: a band above it from before can still shrink. */
    private fun count(before: Int, after: Int, max: Int, what: String): Rejection? =
        if (after > max && after > before) Rejection(RejectionCode.CONFLICT, "At most $max $what", RejectionReason.LIMIT_REACHED) else null

    private fun invalid(message: String) = Rejection(RejectionCode.INVALID, message, RejectionReason.INVALID_INPUT)

}
