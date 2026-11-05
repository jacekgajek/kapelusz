package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundDefault
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import kotlinx.datetime.LocalDate

/**
 * What the current user may do, for showing only the actions that would be accepted. Every answer comes from the
 * shared [Permissions] (the same rules the server enforces), asked with a representative operation.
 */
class Capabilities(private val band: Band, private val userId: String) {
    private val actor = Actor.User(userId)
    private val meId: Id? = band.memberOfUser(userId)?.id

    fun allowed(op: Operation): Boolean = Permissions.isAllowed(band, actor, op)

    /** Sees only their own events, costs and payouts. */
    val artist: Boolean get() = band.isArtist(userId)
    fun setMemberShare() = allowed(SetMemberShare(PROBE, DEFAULT_WEIGHT, null))

    fun createEvent() = allowed(CreateEvent(PROBE, PROBE, PROBE_DATE))
    fun archiveEvents() = allowed(SetEventArchived(PROBE, true))
    fun editEvent(event: Event) = open(event) && allowed(UpdateEvent(event.id))
    fun manageParticipants(event: Event) = open(event) && allowed(AddGuest(event.id, PROBE, PROBE))
    fun editRefunds(event: Event) = open(event) && allowed(SetRefund(event.id, PROBE, Money.ZERO))
    fun settleAll(event: Event) = open(event) && allowed(SettleSuggestions(event.id))
    fun recordTransfer(event: Event, from: Id, to: Id) = open(event) && allowed(RecordTransfer(event.id, PROBE, from, to, Money(1)))

    /** A suggestion's transfer, where a pair settling together is either partner. */
    fun settleSuggestion(event: Event, from: Id, to: Id) = open(event) && allowed(RecordTransfer(event.id, PROBE, from, to, Money(1), together = true))

    /** Leads any; the one who recorded it, their own. */
    fun removeTransfer(event: Event, transfer: Transfer) = open(event) && allowed(RemoveTransfer(event.id, transfer.id))

    /** Parties the user may record collected money for in this event (themselves, or anyone for leads). */
    fun holdingParties(event: Event): List<Id> = if (!open(event)) emptyList() else SettlementCalculator.partyOrder(band, event).filter { !Artists.isArtist(band, it) && allowed(AddHolding(event.id, PROBE, it, Money(1), IncomeSource.OTHER)) }

    /** A fee the organiser still owes: leads record it; who it was paid to marks it received too. */
    fun recordAwaited(event: Event) = open(event) && allowed(AddHolding(event.id, PROBE, ORGANISER, Money(1), IncomeSource.CONTRACT))

    /**
     * Who the user may say an awaited fee was paid to: someone in the event who may hold money, or the band fund when it
     * is on; for leads anyone, else themselves (and the fund for its holder).
     */
    fun receiveParties(event: Event): List<Id> = if (!open(event)) {
        emptyList()
    } else {
        (SettlementCalculator.partyOrder(band, event).filter { !Artists.isArtist(band, it) } + listOfNotNull(BAND_FUND.takeIf { band.treasurerMemberId != null }))
            .filter { allowed(ReceiveAwaited(event.id, PROBE, PROBE, it, Money(1))) }
    }

    fun editHolding(event: Event, holding: Holding) = open(event) && allowed(UpdateHolding(event.id, holding.id))
    fun addCost() = meId != null && allowed(AddCost(PROBE, meId, Money(1), PROBE_DATE))

    /** A cost paid by [memberId]: their own for members and artists, anyone's for leads. */
    fun addCostFor(memberId: Id) = allowed(AddCost(PROBE, memberId, Money(1), PROBE_DATE))

    /** A cost for this event: some active participant the user may add a cost for (leads: anyone in it; members: themselves). */
    fun addCostIn(event: Event) = open(event) && event.participants.any { participant ->
        band.member(participant.partyId)?.archived == false && allowed(AddCost(PROBE, participant.partyId, Money(1), PROBE_DATE))
    }
    fun editCost(cost: Cost) = allowed(UpdateCost(cost.id))
    fun deleteCost(cost: Cost) = allowed(DeleteCost(cost.id))

    /** Adding (or renaming) a file to what [owner] names; a locked cost's owner still may. */
    fun attachTo(owner: AttachmentOwner) = allowed(AddAttachment(owner, PROBE_ATTACHMENT))
    fun detachFrom(owner: AttachmentOwner) = allowed(RemoveAttachment(owner, PROBE))
    fun manageRoster() = allowed(AddMember(PROBE, PROBE))
    fun seeOthersDetails() = band.memberOfUser(userId)?.role?.atLeast(Role.MEMBER) ?: false

    /** Sees everything and does nothing: no money, no costs of their own. */
    val viewer: Boolean get() = band.memberOfUser(userId)?.role == Role.VIEWER

    /** Everyone's costs, not only one's own: leads, and viewers who have none. */
    fun seeOthersCosts() = band.memberOfUser(userId)?.role?.atLeast(Role.LEAD) == true || viewer

    /** Place lookups for the event editor; the server refuses others (PlacesHandlers). */
    fun searchPlaces() = band.memberOfUser(userId)?.role?.atLeast(Role.LEAD) ?: false

    /** The settings' secret menu, for the band's leaders. */
    fun seeRawData() = band.memberOfUser(userId)?.role?.atLeast(Role.LEAD) ?: false

    /** Asking the server to delete the band; the owner only, as the API enforces. */
    fun deleteBand() = band.memberOfUser(userId)?.role == Role.OWNER

    fun transferOwnership() = band.memberOfUser(userId)?.role == Role.OWNER && allowed(TransferOwnership(PROBE))
    fun addFundEntry() = band.treasurerMemberId != null && allowed(AddFundEntry(PROBE, PROBE_DATE, PROBE, Money(1)))
    fun refundCostFromFund() = allowed(AddFundEntry(PROBE, PROBE_DATE, PROBE, Money(1), costId = "1"))
    fun editFundEntry(entry: FundEntry) = addFundEntry() && allowed(UpdateFundEntry(entry.id))

    /** The event's money to or from the fund: leads, while the fund is on and the event open. */
    fun setEventFund(event: Event) = open(event) && band.treasurerMemberId != null && allowed(SetFundAmount(event.id, Money(1)))

    /** Only who left no trace, and never the owner or yourself; anyone else is archived instead. */
    fun deleteMember(member: Member) = member.role != Role.OWNER && member.id != meId && allowed(DeleteMember(member.id)) && MemberDeletion.possible(band, member.id)
    fun renameBand() = allowed(RenameBand(PROBE))
    fun cancelBandDeletion() = allowed(CancelBandDeletion)
    fun setTreasurer() = allowed(SetTreasurer(PROBE))

    /** Turning the fund off: leads, once it holds nothing and no event owes it or expects money from it. */
    fun disableFund() = allowed(DisableBandFund) && BandFund.canDisable(band)
    fun setFundDefault() = allowed(SetFundDefault(null))

    /** The fund's BLIK phone or bank account; one probe stands for both. */
    fun setFundPayment() = allowed(SetFundBlikPhone(null))

    /** [member]'s own BLIK phone or bank account; one probe stands for both. */
    fun setPayment(member: Member) = allowed(SetBlikPhone(member.id, null))
    fun editAgendaTemplate() = allowed(SetAgendaTemplate(emptyList()))

    /** Archived events are read-only until restored. */
    private fun open(event: Event) = !event.archived

    private companion object {
        const val PROBE = "probe"
        val PROBE_DATE = LocalDate(2000, 1, 1)
        val PROBE_ATTACHMENT = Attachment(PROBE, AttachmentKind.IMAGE)
    }
}
