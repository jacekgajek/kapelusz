package com.jacekgajek.kapelusz.history

import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import kotlinx.serialization.Serializable

/** The band-wide settings an operation may change, as one value; undo compares and restores them one by one. */
@Serializable
data class BandSettings(
    val name: String,
    val treasurerMemberId: Id? = null,
    val agendaTemplate: List<AgendaTemplateItem>? = null,
    val fundDefault: FundCut? = null,
    val fundBlikPhone: String? = null,
    val fundBankAccount: String? = null,
)

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

/** What one operation changed: the touched parts of the band before and after it. */
@Serializable
data class BandChange(val before: BandPart, val after: BandPart) {
    val isEmpty: Boolean get() = before == BandPart() && after == BandPart()

    /** Undoing would bring back attachments whose files the server deleted when they were removed. */
    val restoresAttachments: Boolean
        get() {
            val now = after.attachmentIds()
            return before.attachmentIds().any { it !in now }
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

internal val Band.settings get() = BandSettings(name, treasurerMemberId, agendaTemplate, fundDefault, fundBlikPhone, fundBankAccount)

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

/** Ids of the attachments in a part of the band: on costs, on events and on their collected entries. */
private fun BandPart.attachmentIds(): Set<Id> = (
    costs.values.filterNotNull().flatMap { it.attachments } +
        events.values.filterNotNull().flatMap { event -> event.attachments + event.holdings.flatMap { it.attachments } }
    ).map { it.id }.toSet()
