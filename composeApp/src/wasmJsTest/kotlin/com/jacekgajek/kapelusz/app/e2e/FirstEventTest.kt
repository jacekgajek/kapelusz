package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_money
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.band_name
import com.jacekgajek.kapelusz.app.generated.collected_empty
import com.jacekgajek.kapelusz.app.generated.create
import com.jacekgajek.kapelusz.app.generated.create_band
import com.jacekgajek.kapelusz.app.generated.create_band_intro
import com.jacekgajek.kapelusz.app.generated.event_name
import com.jacekgajek.kapelusz.app.generated.events_empty
import com.jacekgajek.kapelusz.app.generated.new_event
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.tab_money
import com.jacekgajek.kapelusz.app.generated.your_name
import io.kotest.core.spec.style.ExpectSpec

/** A new user's first evening with the app: sign in, create a band and an event, record the money it brought. */
class FirstEventTest :
    ExpectSpec({
        e2e("a new user signs in, creates a band and an event, and records the money received") {
            val user = freshUser("Alice")
            // The development sign-in: type a name, then tap the button that signs in as that name.
            type("Test user (local server)", user)
            tap("Sign in as $user")

            see(Res.string.create_band_intro)
            type(Res.string.band_name, "Night Owls")
            type(Res.string.your_name, "Alice")
            tap(Res.string.create_band)

            see(Res.string.events_empty)
            tap(Res.string.new_event)
            type(Res.string.event_name, "Jazz Night")
            tap(Res.string.create)

            // The event opens on its line-up. There is a chip for each member not in it yet; a tap adds Alice.
            tap("Jazz Night")
            tap("Alice")

            tap(Res.string.tab_money)
            see(Res.string.collected_empty)
            tap(Res.string.add_money)
            type(Res.string.amount, "1200")
            tap(Res.string.save)

            see("1,200.00")
        }
    })
