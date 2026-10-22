package com.jacekgajek.kapelusz.app.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The serial names are the route strings, which the web app shows in the address bar (`#settings`, `#event/<id>`):
// keep them short and stable, since bookmarks and shared links contain them.

@Serializable @SerialName("events") data object EventsRoute
@Serializable @SerialName("costs") data object MyCostsRoute
@Serializable @SerialName("settle") data object SettleRoute
@Serializable @SerialName("fund") data object FundRoute
@Serializable @SerialName("event") data class EventRoute(val eventId: String)

/** Add (`costId == null`) or edit a cost. */
@Serializable @SerialName("cost") data class CostRoute(val costId: String? = null, val forEventId: String? = null)
/** Settings, or one of its pages ([page] is a [com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsPage] name). */
@Serializable @SerialName("settings") data class SettingsRoute(val page: String? = null)
@Serializable @SerialName("members") data object RosterRoute
@Serializable @SerialName("new-band") data object NewBandRoute
@Serializable @SerialName("history") data object HistoryRoute
