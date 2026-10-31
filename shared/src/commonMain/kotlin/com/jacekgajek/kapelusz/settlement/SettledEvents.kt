package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event

/**
 * A band state that knows which of its events are settled, however it found out; the rules that depend on it take
 * this, so that settling every event once (as [com.jacekgajek.kapelusz.view.BandView] does) serves them all.
 */
interface SettledEvents {
    val band: Band

    fun isSettled(event: Event): Boolean
}
