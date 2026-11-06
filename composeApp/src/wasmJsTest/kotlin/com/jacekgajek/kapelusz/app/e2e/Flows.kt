package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_member
import com.jacekgajek.kapelusz.app.generated.add_money
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.band_name
import com.jacekgajek.kapelusz.app.generated.check_code
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.collected_by
import com.jacekgajek.kapelusz.app.generated.create
import com.jacekgajek.kapelusz.app.generated.create_band
import com.jacekgajek.kapelusz.app.generated.create_band_intro
import com.jacekgajek.kapelusz.app.generated.create_invite
import com.jacekgajek.kapelusz.app.generated.event_name
import com.jacekgajek.kapelusz.app.generated.events_empty
import com.jacekgajek.kapelusz.app.generated.fund_enable
import com.jacekgajek.kapelusz.app.generated.fund_treasurer
import com.jacekgajek.kapelusz.app.generated.invite_code
import com.jacekgajek.kapelusz.app.generated.invites_unavailable
import com.jacekgajek.kapelusz.app.generated.join
import com.jacekgajek.kapelusz.app.generated.join_band
import com.jacekgajek.kapelusz.app.generated.member_name
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.menu_settings
import com.jacekgajek.kapelusz.app.generated.new_event
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.generated.sign_out
import com.jacekgajek.kapelusz.app.generated.sync_synced
import com.jacekgajek.kapelusz.app.generated.tab_gig
import com.jacekgajek.kapelusz.app.generated.tab_money
import com.jacekgajek.kapelusz.app.generated.your_name

// Steps that many scenarios share. Each one is a few taps on the screen.

/** The development sign-in. Type a name, then tap the button that signs in as that name. */
internal suspend fun Screen.signIn(user: String) {
    type("Test user (local server)", user)
    tap("Sign in as $user")
}

/** Signs out from the settings screen. Starts on a band's screen, ends on the sign-in screen. */
internal suspend fun Screen.signOut() {
    tap(Res.string.settings)
    pick(Res.string.settings)
    tap(Res.string.sign_out)
    see("Test user (local server)")
}

/** Creates a band on the zero screen. Ends on its empty events list. */
internal suspend fun Screen.createBand(name: String, me: String) {
    see(Res.string.create_band_intro)
    type(Res.string.band_name, name)
    type(Res.string.your_name, me)
    tap(Res.string.create_band)
    see(Res.string.events_empty)
    // The band members screen asks the server for invites. Wait until the band is on the server.
    see(Res.string.sync_synced)
}

/** Creates an event from the events list. The new event opens. */
internal suspend fun Screen.createEvent(name: String) {
    tap(Res.string.new_event)
    type(Res.string.event_name, name)
    tap(Res.string.create)
    see(Res.string.tab_gig)
}

/** Adds a member on the band members screen and returns their invite code. Starts and ends on the events list. */
internal suspend fun Screen.inviteMember(name: String): String {
    // The band members screen asks the server for invites once. Right after the band was created, the server may
    // not have the band yet. Then the screen says so and does not ask again. Open it again until it has them.
    for (attempt in 1..5) {
        tap(Res.string.settings)
        pick(Res.string.menu_roster)
        if (!shown(Res.string.invites_unavailable, seconds = 2)) break
        tap("Back")
    }
    tap(Res.string.add_member)
    type(Res.string.member_name, name)
    tap(Res.string.ok)
    // The invite is created on the server, so the server must know the new member first. The app pushes a change
    // about a second after it was made. The sync status does not show that short wait.
    wait(3, "the new member is pushed to the server")
    // The new member has no account yet. Their row opens a dialog with an invite to create. The role stays Member.
    tap(name)
    tap(Res.string.create_invite)
    val code = read(Regex("[A-Z0-9]{6}"))
    tap(Res.string.close)
    tap("Back")
    return code
}

/** Joins a band with an invite code, from the zero screen. Ends on the band's events list. */
internal suspend fun Screen.joinBand(code: String) {
    tap(Res.string.join_band)
    type(Res.string.invite_code, code)
    // Check shows which band the code is for. Join then joins it.
    tap(Res.string.check_code)
    tap(Res.string.join)
}

/** Opens the app's settings screen from a band's screen. */
internal suspend fun Screen.openSettings() {
    tap(Res.string.settings)
    pick(Res.string.menu_settings)
}

/** Turns the band fund on, held by [holder]. Starts on the events list and ends there. */
internal suspend fun Screen.enableFund(holder: String) {
    openSettings()
    tap(Res.string.band_fund)
    tap(Res.string.fund_enable)
    tap(Res.string.fund_treasurer)
    pick(holder)
    tap("Back")
    tap("Back")
}

/** Records money received at the open event, on its Money tab. [by] is the member who holds it; null means me. */
internal suspend fun Screen.receiveMoney(amount: String, by: String? = null) {
    tap(Res.string.tab_money)
    tap(Res.string.add_money)
    if (by != null) {
        tap(Res.string.collected_by)
        pick(by)
    }
    type(Res.string.amount, amount)
    tap(Res.string.save)
}
