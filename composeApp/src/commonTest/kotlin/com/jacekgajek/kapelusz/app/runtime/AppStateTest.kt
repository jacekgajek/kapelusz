package com.jacekgajek.kapelusz.app.runtime

import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.auth.fakeIdToken
import com.jacekgajek.kapelusz.app.bands.BandState
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** What the app's root shows, from its parts ([appState]). */
class AppStateTest :
    ExpectSpec({
        val session = Session.fromIdToken(fakeIdToken("sub-1", 2_000_000_000))

        expect("an outdated app is told first whatever else is true") {
            appState(updateRequired = true, session, BandState.NoBand, signedOutEverywhere = false) shouldBe AppState.UpdateRequired
            appState(updateRequired = true, null, BandState.Loading, signedOutEverywhere = true) shouldBe AppState.UpdateRequired
        }

        expect("without a session the sign-in screen says why when signed out everywhere") {
            appState(false, null, BandState.Loading, signedOutEverywhere = false) shouldBe AppState.SignedOut(everywhere = false)
            appState(false, null, BandState.NoBand, signedOutEverywhere = true) shouldBe AppState.SignedOut(everywhere = true)
        }

        expect("a session shows the band as it stands") {
            appState(false, session, BandState.NoBand, signedOutEverywhere = true) shouldBe AppState.SignedIn(session, BandState.NoBand)
            val loading = appState(false, session, BandState.Loading, signedOutEverywhere = false)
            loading shouldBe AppState.SignedIn(session, BandState.Loading)
        }
    })
