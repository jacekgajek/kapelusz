package com.jacekgajek.kapelusz.app.runtime

import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.bands.BandState

/** The one thing the app's root switches on (REFACTORING.md 3.3): what the user sees now. */
sealed interface AppState {
    /** The server no longer serves this app version (ITERATION_2 §2): "update to continue". */
    data object UpdateRequired : AppState

    /** Nobody is signed in; [everywhere]: because of "sign out on all devices" (ITERATION_3 R6), which the screen says. */
    data class SignedOut(val everywhere: Boolean) : AppState

    /** [session]'s user, with the band on this device as it stands. */
    data class SignedIn(val session: Session, val band: BandState) : AppState
}

/** The state from its parts: an outdated app first, then whether anyone is signed in. */
fun appState(updateRequired: Boolean, session: Session?, band: BandState, signedOutEverywhere: Boolean): AppState = when {
    updateRequired -> AppState.UpdateRequired
    session == null -> AppState.SignedOut(signedOutEverywhere)
    else -> AppState.SignedIn(session, band)
}
