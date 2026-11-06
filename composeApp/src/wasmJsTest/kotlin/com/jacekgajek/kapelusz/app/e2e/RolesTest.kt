package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_money
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.new_event
import com.jacekgajek.kapelusz.app.generated.role
import com.jacekgajek.kapelusz.app.generated.role_viewer
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.generated.tab_money
import io.kotest.core.spec.style.ExpectSpec

/** A viewer sees the band but changes nothing. Alice makes Bob a viewer after he joined. */
class RolesTest :
    ExpectSpec({
        e2e("a viewer has no buttons to add events or money") {
            val alice = freshUser("Alice")
            val bob = freshUser("Bob")

            signIn(alice)
            createBand("Night Owls", "Alice")
            val code = inviteMember("Bob")
            createEvent("Jazz Night")
            tap("Alice")
            tap("Back")
            wait(3, "the event is pushed to the server before signing out")
            signOut()

            signIn(bob)
            joinBand(code)
            see("Jazz Night")
            signOut()

            // Bob's role, on his row of the band members screen.
            signIn(alice)
            tap(Res.string.settings)
            pick(Res.string.menu_roster)
            tap("Bob")
            edit(Res.string.role)
            tap(Res.string.role_viewer)
            tap(Res.string.close)
            tap("Back")
            wait(3, "the role change is pushed to the server before signing out")
            signOut()

            signIn(bob)
            see("Jazz Night")
            missing(Res.string.new_event)
            tap("Jazz Night")
            tap(Res.string.tab_money)
            missing(Res.string.add_money)
        }
    })
