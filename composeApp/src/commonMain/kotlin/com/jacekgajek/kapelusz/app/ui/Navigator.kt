package com.jacekgajek.kapelusz.app.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.jacekgajek.kapelusz.app.data.Incoming
import com.jacekgajek.kapelusz.app.resetTo

// Where a screen can go, as small interfaces: a screen names what it needs, and only [AppNavigator] knows the routes.

/** From the first screens: nothing to go back to. */
interface BackNav {
    fun back()
}

/** The tab screens: their top bar's places, and what their rows open. */
interface TabNav {
    fun settings()

    fun roster()

    fun openEvent(eventId: String)
}

interface CostsNav : TabNav {
    fun openCost(costId: String)

    fun addCost()
}

interface FundNav : TabNav {
    fun addCost()
}

interface EventNav : BackNav {
    fun addCost(eventId: String)

    fun openCost(costId: String)

    /** Band members at [memberId]'s dialog, to invite them (ITERATION_5 §12). */
    fun inviteMember(memberId: String)
}

interface CostNav : BackNav {
    /** The event's Money page, where the refund is. */
    fun openRefunds(eventId: String)

    /** A lead added a cost for someone else: "My costs" shows everyone's, so they see it. */
    fun addedForOther()

    /** Added from the band fund's +: back there to refund it (ITERATION_7 §5.1). */
    fun added(costId: String)
}

interface SummaryNav : BackNav {
    fun inviteMember(memberId: String)

    fun played()
}

interface PlayedNav : BackNav {
    fun openEvent(eventId: String)
}

interface SettingsNav : BackNav {
    fun members()

    fun bandGone()
}

/** The one implementation, over the navigation controller; built once per band in `Navigation`. */
class AppNavigator(private val navController: NavHostController, private val incoming: Incoming) :
    CostsNav,
    FundNav,
    EventNav,
    CostNav,
    SummaryNav,
    PlayedNav,
    SettingsNav {

    override fun settings() = navController.navigate(SettingsRoute())

    override fun roster() = navController.navigate(RosterRoute())

    override fun members() = roster()

    override fun openEvent(eventId: String) = navController.navigate(EventRoute(eventId))

    override fun openCost(costId: String) = navController.navigate(CostRoute(costId))

    override fun addCost() = navController.navigate(CostRoute())

    override fun addCost(eventId: String) = navController.navigate(CostRoute(forEventId = eventId))

    override fun inviteMember(memberId: String) = navController.navigate(RosterRoute(memberId))

    override fun played() = navController.navigate(PlayedRoute)

    override fun bandGone() = navController.resetTo(EventsRoute)

    override fun openRefunds(eventId: String) {
        incoming.pendingEventSection.value = eventId to "REFUNDS"
        navController.navigate(EventRoute(eventId))
    }

    override fun addedForOther() {
        navController.previousBackStackEntry?.savedStateHandle?.set(SHOW_EVERYONE, true)
    }

    override fun added(costId: String) {
        val fund = navController.previousBackStackEntry?.takeIf { it.destination.hasRoute(FundRoute::class) }
        fund?.savedStateHandle?.set(ADDED_COST, costId)
    }

    /**
     * Only from a screen that is fully shown, and never past the first one: a second quick tap during the fade-out
     * would pop the list too and leave a blank screen.
     */
    override fun back() {
        val shown = navController.currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (navController.previousBackStackEntry != null && shown) navController.popBackStack()
    }

    companion object {
        /** Saved state key of the "everyone's costs" switch on the costs tab. */
        const val SHOW_EVERYONE = "showEveryone"

        /** Saved state key of a cost just added from the band fund's +, for the fund tab to refund. */
        const val ADDED_COST = "addedCost"
    }
}
