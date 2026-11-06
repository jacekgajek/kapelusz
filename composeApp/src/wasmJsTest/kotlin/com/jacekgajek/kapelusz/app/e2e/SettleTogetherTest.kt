package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.generated.settle_together_accept
import com.jacekgajek.kapelusz.app.generated.settle_together_invite
import com.jacekgajek.kapelusz.app.generated.tab_settlement
import io.kotest.core.spec.style.ExpectSpec

/** Alice and Bob settle together. On the Split tab they are one party, and Carol gets her share from them. */
class SettleTogetherTest :
    ExpectSpec({
        e2e("two members who settle together are one party in the split") {
            val alice = freshUser("Alice")
            val bob = freshUser("Bob")

            signIn(alice)
            createBand("Night Owls", "Alice")
            val code = inviteMember("Bob")
            inviteMember("Carol")
            createEvent("Jazz Night")
            tap("Alice")
            tap("Bob")
            tap("Carol")
            receiveMoney("1200")
            tap("Back")
            wait(3, "the event is pushed to the server before signing out")
            signOut()

            // Only members with an account can settle together. Bob joins first.
            signIn(bob)
            joinBand(code)
            see("Jazz Night")
            signOut()

            // Alice asks Bob to settle together, from his row on the band members screen.
            signIn(alice)
            tap(Res.string.settings)
            pick(Res.string.menu_roster)
            tap("Bob")
            tap(Res.string.settle_together_invite)
            tap(Res.string.close)
            tap("Back")
            wait(3, "the invitation is pushed to the server before signing out")
            signOut()

            // Bob accepts on his events list. The split then shows the pair as one party.
            signIn(bob)
            tap(Res.string.settle_together_accept)
            tap("Jazz Night")
            tap(Res.string.tab_settlement)
            see("Alice and Bob")
            see("800.00")
            see("400.00")
        }
    })
