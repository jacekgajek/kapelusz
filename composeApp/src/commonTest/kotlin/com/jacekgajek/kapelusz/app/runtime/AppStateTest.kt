package com.jacekgajek.kapelusz.app.runtime

import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.auth.fakeIdToken
import com.jacekgajek.kapelusz.app.bands.BandState
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the app's root shows, from its parts ([appState]). */
class AppStateTest {
    private val session = Session.fromIdToken(fakeIdToken("sub-1", 2_000_000_000))

    @Test
    fun anOutdatedAppIsToldFirstWhateverElseIsTrue() {
        assertEquals(AppState.UpdateRequired, appState(updateRequired = true, session, BandState.NoBand, signedOutEverywhere = false))
        assertEquals(AppState.UpdateRequired, appState(updateRequired = true, null, BandState.Loading, signedOutEverywhere = true))
    }

    @Test
    fun withoutASessionTheSignInScreenSaysWhyWhenSignedOutEverywhere() {
        assertEquals(AppState.SignedOut(everywhere = false), appState(false, null, BandState.Loading, signedOutEverywhere = false))
        assertEquals(AppState.SignedOut(everywhere = true), appState(false, null, BandState.NoBand, signedOutEverywhere = true))
    }

    @Test
    fun aSessionShowsTheBandAsItStands() {
        assertEquals(AppState.SignedIn(session, BandState.NoBand), appState(false, session, BandState.NoBand, signedOutEverywhere = true))
        val loading = appState(false, session, BandState.Loading, signedOutEverywhere = false)
        assertEquals(AppState.SignedIn(session, BandState.Loading), loading)
    }
}
