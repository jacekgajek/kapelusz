package com.jacekgajek.artsplit.app.ui

import kotlinx.serialization.Serializable

@Serializable data object EventsRoute
@Serializable data object MyCostsRoute
@Serializable data object SettleRoute
@Serializable data class EventRoute(val eventId: String)

/** Add (`costId == null`) or edit a cost. */
@Serializable data class CostRoute(val costId: String? = null)
@Serializable data object SettingsRoute
@Serializable data object RosterRoute
