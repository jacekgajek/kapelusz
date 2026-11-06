package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_paid
import com.jacekgajek.kapelusz.app.generated.settle_received
import com.jacekgajek.kapelusz.app.generated.status_settled
import com.jacekgajek.kapelusz.app.generated.tab_settlement
import io.kotest.core.spec.style.ExpectSpec

/**
 * A settlement recorded from each side. Bob holds the money. Alice, the leader, records what she received from Bob
 * and what Bob gave Carol. At another event Bob records what he paid himself.
 */
class SettlementTest :
    ExpectSpec({
        e2e("the receiver, the leader and the payer each record a transfer") {
            val alice = freshUser("Alice")
            val bob = freshUser("Bob")

            signIn(alice)
            createBand("Night Owls", "Alice")
            val code = inviteMember("Bob")
            inviteMember("Carol")

            // Jazz Night: three play, Bob holds 1200. Everyone gets 400.
            createEvent("Jazz Night")
            tap("Alice")
            tap("Bob")
            tap("Carol")
            receiveMoney("1200", by = "Bob")
            tap(Res.string.tab_settlement)
            see("400.00")
            // Alice received hers from Bob.
            tap(Res.string.settle_received)
            // Bob gave Carol hers. Alice records it for them.
            tap(Res.string.settle)
            see(Res.string.status_settled)
            tap("Back")

            // Blues Night: Bob and Carol play, Bob holds 600.
            createEvent("Blues Night")
            tap("Bob")
            tap("Carol")
            receiveMoney("600", by = "Bob")
            tap("Back")
            wait(3, "the events are pushed to the server before signing out")
            signOut()

            // Bob paid Carol her 300 and records it himself.
            signIn(bob)
            joinBand(code)
            tap("Blues Night")
            tap(Res.string.tab_settlement)
            see("300.00")
            tap(Res.string.settle_paid)
            see(Res.string.status_settled)
        }
    })
