package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.tab_money
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import io.kotest.core.spec.style.ExpectSpec

/** Two people in one band. Alice invites Bob. Bob joins with the code and adds a cost. Alice sees it on the event. */
class BandmateTest :
    ExpectSpec({
        e2e("a bandmate joins with an invite code, and the leader sees their cost on the event") {
            val alice = freshUser("Alice")
            val bob = freshUser("Bob")

            signIn(alice)
            createBand("Night Owls", "Alice")
            val code = inviteMember("Bob")
            // Both play at the event. Bob is in the band already, as a member without an account yet.
            createEvent("Jazz Night")
            tap("Alice")
            tap("Bob")
            tap("Back")
            wait(3, "the event is pushed to the server before signing out")
            signOut()

            signIn(bob)
            joinBand(code)
            see("Jazz Night")
            tap(Res.string.tab_my_costs)
            tap(Res.string.add_cost)
            type(Res.string.amount, "60")
            tap(Res.string.save)
            see("60.00")
            wait(3, "the cost is pushed to the server before signing out")
            signOut()

            // Alice's app loads the band from the server again, with Bob's cost.
            signIn(alice)
            tap("Jazz Night")
            tap(Res.string.tab_money)
            see("Bob")
            see("60.00")
        }
    })
