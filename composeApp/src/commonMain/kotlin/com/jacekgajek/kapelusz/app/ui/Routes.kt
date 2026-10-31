package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.settings.EventTab
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The serial names are the route strings, which the web app shows in the address bar (`#settings`, `#event/<id>`):
// keep them short and stable, since bookmarks and shared links contain them.

@Serializable
@SerialName("events")
data object EventsRoute

@Serializable
@SerialName("costs")
data object MyCostsRoute

@Serializable
@SerialName("settle")
data object SettleRoute

@Serializable
@SerialName("fund")
data object FundRoute

/**
 * An event; at one of its [section]s when given (e.g. where shared files went, or the refund a cost is in), with what
 * the inbox holds for it under [carryId] (files shared to the app).
 */
@Serializable
@SerialName("event")
data class EventRoute(val eventId: String, val section: String? = null, val carryId: String? = null) {
    /** [section] by name: the web's navigation has no type for an enum argument (it works on the JVM by reflection). */
    constructor(eventId: String, section: EventSection?, carryId: String? = null) : this(eventId, section?.name, carryId)
}

/** Add (`costId == null`, [forEventId]'s when given, with the shared files under [carryId]) or edit a cost. */
@Serializable
@SerialName("cost")
data class CostRoute(val costId: String? = null, val forEventId: String? = null, val carryId: String? = null)

/** Settings, or one of its pages ([page] is a [com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsPage] name). */
@Serializable
@SerialName("settings")
data class SettingsRoute(val page: String? = null)

/** Band members; with [memberId], that member's dialog open (e.g. to invite them). */
@Serializable
@SerialName("members")
data class RosterRoute(val memberId: String? = null)

@Serializable
@SerialName("new-band")
data object NewBandRoute

@Serializable
@SerialName("history")
data object HistoryRoute

@Serializable
@SerialName("summary")
data object SummaryRoute

/** "Where we played", for the Summary's period. */
@Serializable
@SerialName("played")
data object PlayedRoute

/**
 * The event screen's sections in list order, each on the pages ([tabs]) it shows on. One list item each, so a section is
 * found by its index on its page (e.g. where shared files were attached).
 */
enum class EventSection(vararg val tabs: EventTab) {
    WHEN(EventTab.GIG),
    DETAILS(EventTab.GIG),
    FILES(EventTab.GIG),
    PARTICIPANTS(EventTab.GIG),
    SHARE_INFO(EventTab.GIG),
    STATUS(EventTab.MONEY, EventTab.SETTLEMENT),
    TOTALS(EventTab.MONEY),
    COLLECTED(EventTab.MONEY),
    REFUNDS(EventTab.MONEY),
    PROBLEMS(EventTab.SETTLEMENT),
    SUMMARY(EventTab.SETTLEMENT),
    TO_SETTLE(EventTab.SETTLEMENT),
    HISTORY(EventTab.SETTLEMENT),
    ;

    companion object {
        fun on(tab: EventTab) = entries.filter { tab in it.tabs }
    }
}
