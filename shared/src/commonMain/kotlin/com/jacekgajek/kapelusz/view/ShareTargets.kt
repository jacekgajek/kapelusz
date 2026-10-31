package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.MAX_ATTACHMENTS
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.rules.isArtist
import com.jacekgajek.kapelusz.settlement.CostStatus
import kotlinx.datetime.LocalDate

/** Where files shared to Kapelusz can go (I6 §1): one of these, in an event of one of the user's bands. */
sealed interface ShareTarget {
    /** How many more files it takes. */
    val room: Int

    data class NewCost(override val room: Int = MAX_ATTACHMENTS) : ShareTarget
    data class NewCollected(override val room: Int = MAX_ATTACHMENTS) : ShareTarget
    data class EventFiles(override val room: Int) : ShareTarget
    data class ExistingCost(val cost: Cost, override val room: Int) : ShareTarget
    data class ExistingCollected(val holding: Holding, override val room: Int) : ShareTarget
}

/** An event shared files can go to, with its band; [upcoming] after today. */
data class ShareEvent(val view: BandView, val event: Event, val upcoming: Boolean) {
    val band: Band get() = view.band
}

object ShareTargets {
    /**
     * The events of [bands] where [userId] can put shared files somewhere: not archived, at least one target. Today's
     * and past events first, newest first (receipts usually come after the money is spent), then upcoming ones, soonest
     * first.
     */
    fun events(bands: List<Band>, userId: String, today: LocalDate): List<ShareEvent> {
        val all = bands.map(::BandView).flatMap { view ->
            val band = view.band
            // Artists see only the events they play (I4 §1.1), even with an own cost in another.
            val me = band.memberOfUser(userId)?.id
            val artist = band.isArtist(userId)
            band.events
                .filter { event ->
                    val mine = !artist || (me != null && event.participant(me) != null)
                    !event.archived && mine && targets(view, userId, event).isNotEmpty()
                }
                .map { ShareEvent(view, it, it.date > today) }
        }
        val (upcoming, past) = all.partition { it.upcoming }
        return past.sortedWith(compareByDescending<ShareEvent> { it.event.date }.thenByDescending { it.event.updatedAt }) +
            upcoming.sortedWith(compareBy<ShareEvent> { it.event.date }.thenBy { it.event.updatedAt })
    }

    /**
     * What the user may attach files to in [event]: a new cost, new collected money, the event's own files, then its
     * existing costs (not those fully refunded) and collected entries. Full ones are listed too, with no room left.
     */
    fun targets(view: BandView, userId: String, event: Event): List<ShareTarget> {
        if (event.archived) return emptyList()
        val band = view.band
        val can = Capabilities(band, userId)
        val statuses = view.costStatuses
        fun mayAttach(owner: AttachmentOwner) = can.allowed(AddAttachment(owner, PROBE))
        return buildList {
            if (can.addCostIn(event)) add(ShareTarget.NewCost())
            if (can.holdingParties(event).isNotEmpty()) add(ShareTarget.NewCollected())
            if (mayAttach(AttachmentOwner.OfEvent(event.id))) add(ShareTarget.EventFiles(MAX_EVENT_ATTACHMENTS - event.attachments.size))
            band.costs.filter { it.eventId == event.id && statuses.status(it) != CostStatus.REFUNDED && mayAttach(AttachmentOwner.OfCost(it.id)) }
                .sortedBy { it.createdAt }
                .forEach { add(ShareTarget.ExistingCost(it, MAX_ATTACHMENTS - it.attachments.size)) }
            event.holdings.filter { mayAttach(AttachmentOwner.OfHolding(event.id, it.id)) }
                .forEach { add(ShareTarget.ExistingCollected(it, MAX_ATTACHMENTS - it.attachments.size)) }
        }
    }

    private val PROBE = Attachment("probe", AttachmentKind.IMAGE)
}
