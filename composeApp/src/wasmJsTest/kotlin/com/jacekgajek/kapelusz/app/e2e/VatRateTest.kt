package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_money
import com.jacekgajek.kapelusz.app.generated.band_name
import com.jacekgajek.kapelusz.app.generated.create
import com.jacekgajek.kapelusz.app.generated.create_band
import com.jacekgajek.kapelusz.app.generated.create_band_intro
import com.jacekgajek.kapelusz.app.generated.event_name
import com.jacekgajek.kapelusz.app.generated.events_empty
import com.jacekgajek.kapelusz.app.generated.gross
import com.jacekgajek.kapelusz.app.generated.new_event
import com.jacekgajek.kapelusz.app.generated.tab_money
import com.jacekgajek.kapelusz.app.generated.vat_mode_net
import com.jacekgajek.kapelusz.app.generated.vat_rate
import com.jacekgajek.kapelusz.app.generated.your_name
import io.kotest.core.spec.style.ExpectSpec

/**
 * A made-up rule, to see what a failing end-to-end test looks like: the VAT field accepts 23 % and refuses any other
 * rate. The app has no such rule (any rate from 1 to 100 is fine), so this test fails on purpose.
 */
class VatRateTest :
    ExpectSpec({
        e2e("the VAT field accepts only 23 % and refuses another rate") {
            val user = freshUser("Vat")
            type("Test user (local server)", user)
            tap("Sign in as $user")
            see(Res.string.create_band_intro)
            type(Res.string.band_name, "Tax Band")
            type(Res.string.your_name, "Vera")
            tap(Res.string.create_band)
            see(Res.string.events_empty)
            tap(Res.string.new_event)
            type(Res.string.event_name, "Audit Night")
            tap(Res.string.create)
            tap("Audit Night")
            tap("Vera")
            tap(Res.string.tab_money)
            tap(Res.string.add_money)

            tap(Res.string.vat_mode_net)
            type(Res.string.gross, "1230")
            type(Res.string.vat_rate, "8")

            see("Only 23 % is accepted")
        }
    })
