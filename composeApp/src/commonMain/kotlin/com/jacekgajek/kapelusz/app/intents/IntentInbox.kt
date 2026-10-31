package com.jacekgajek.kapelusz.app.intents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the app was asked to do and has not done yet ([AppIntent]), until its one consumer (the `IntentRouter`) takes
 * it: a link survives signing in, and the web keeps it over a reload. At most one of a kind.
 */
class IntentInbox {
    private val _pending = MutableStateFlow<List<AppIntent>>(emptyList())

    /** In the order offered. */
    val pending: StateFlow<List<AppIntent>> = _pending.asStateFlow()

    private val carries = mutableMapOf<String, Carry>()
    private var nextCarry = 0

    /** A newer one of the same kind replaces the one waiting (a second share starts over). */
    fun offer(intent: AppIntent) {
        // Files handed to a form earlier but never taken: not to turn up there later.
        if (intent is AppIntent.ShareFiles) carries.clear()
        _pending.update { list -> list.filterNot { it::class == intent::class } + intent }
    }

    /** Done with it (acted on, or dismissed); nothing if it is not waiting any more. */
    fun take(intent: AppIntent) {
        _pending.update { it - intent }
    }

    /** Keeps [carry] for the screen it goes to, which takes it once with [takeCarry] by the returned id. */
    fun hold(carry: Carry): String {
        val id = "carry${nextCarry++}"
        carries[id] = carry
        return id
    }

    fun takeCarry(id: String): Carry? = carries.remove(id)

    /** What was resolved for the user who signed out goes; a link not resolved yet waits for the next user. */
    fun signedOut() {
        carries.clear()
        _pending.update { list -> list.filterNot { it is AppIntent.OpenEvent && it.bandId != null } }
    }
}
