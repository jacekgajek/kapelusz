package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.confirm
import com.jacekgajek.kapelusz.app.generated.description
import com.jacekgajek.kapelusz.app.generated.fund_add_entry
import com.jacekgajek.kapelusz.app.generated.fund_entry_from
import com.jacekgajek.kapelusz.app.generated.fund_entry_refund_to
import com.jacekgajek.kapelusz.app.generated.fund_in
import com.jacekgajek.kapelusz.app.generated.fund_out
import com.jacekgajek.kapelusz.app.generated.fund_refund_cost
import com.jacekgajek.kapelusz.app.generated.fund_select_all
import com.jacekgajek.kapelusz.app.generated.fund_several
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.paid_by
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import com.jacekgajek.kapelusz.app.generated.tab_settlement
import com.jacekgajek.kapelusz.app.generated.total_fund
import io.kotest.core.spec.style.ExpectSpec

/** The band fund: money in from the members, a cost refunded out of it, and an event's share into it. */
class BandFundTest :
    ExpectSpec({
        e2e("members pay in at once, and a cost is refunded from the fund") {
            signIn(freshUser("Alice"))
            createBand("Night Owls", "Alice")
            inviteMember("Bob")
            enableFund("Alice")

            // 50 from each of the two members, in one entry.
            tap(Res.string.tab_fund)
            tap(Res.string.fund_add_entry)
            tap(Res.string.fund_in)
            type(Res.string.amount, "50")
            tap(Res.string.fund_several)
            tap(Res.string.fund_select_all)
            tap(Res.string.ok)
            type(Res.string.description, "Membership fees")
            tap(Res.string.save)
            see(text(Res.string.fund_entry_from, "Alice"))
            see(text(Res.string.fund_entry_from, "Bob"))
            see("100.00")

            // Bob paid 60 for the band. The fund refunds it.
            tap(Res.string.tab_my_costs)
            tap(Res.string.add_cost)
            tap(Res.string.paid_by)
            pick("Bob")
            type(Res.string.amount, "60")
            tap(Res.string.save)
            tap(Res.string.tab_fund)
            tap(Res.string.fund_add_entry)
            tap(Res.string.fund_out)
            tap(Res.string.fund_refund_cost)
            pick("60.00")
            tap(Res.string.save)
            see(text(Res.string.fund_entry_refund_to, "Bob"))
            see("40.00")
        }

        e2e("an event's share goes into the fund") {
            signIn(freshUser("Alice"))
            createBand("Night Owls", "Alice")
            inviteMember("Bob")
            enableFund("Alice")

            // Alice holds 1200. 200 of it is for the fund; she keeps it as its holder.
            createEvent("Jazz Night")
            tap("Alice")
            tap("Bob")
            receiveMoney("1200")
            edit(Res.string.total_fund)
            type(Res.string.amount, "200")
            tap(Res.string.save)
            see("200.00")
            // The split suggests that Alice keeps the fund's 200. She confirms.
            tap(Res.string.tab_settlement)
            tap(Res.string.confirm)
            tap("Back")

            tap(Res.string.tab_fund)
            see("Jazz Night")
            see("200.00")
        }
    })
