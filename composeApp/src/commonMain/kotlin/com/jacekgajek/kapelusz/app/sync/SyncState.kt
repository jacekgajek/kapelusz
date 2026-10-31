package com.jacekgajek.kapelusz.app.sync

/**
 * Where the sync loop is (REFACTORING.md 3.5): one value instead of a job, a drain job, a status used as a guard and
 * a backoff variable. [reduce] moves it on a [SyncInput]; what to do next comes out as an [Effect].
 */
sealed interface SyncState {
    /** Not on screen (Android), or never started: nothing runs. */
    data object Stopped : SyncState

    /** Between rounds; [backoff] is 0 after a good one, else how long until the next try. */
    data class Idle(val backoff: Long) : SyncState

    /** A round runs; [backoff]: carried from before. */
    data class Running(val backoff: Long = 0) : SyncState

    /** The web tab was hidden (ITERATION_7 §2.6): what waits goes first, with [backoff] while offline, then [Stopped]. */
    data class Draining(val backoff: Long, val running: Boolean) : SyncState

    /** No round until the user acts: signed in again, or updated the app. [loopWanted]: the loop goes on afterwards. */
    data class Blocked(val why: SyncStatus, val loopWanted: Boolean) : SyncState
}

/** Waiting for the next round (a [Effect.WaitThenRun] is under way). */
val SyncState.betweenRounds: Boolean
    get() = this is SyncState.Idle || (this is SyncState.Draining && !running)

/** What moves the loop on. */
sealed interface SyncInput {
    data object Start : SyncInput
    data object Stop : SyncInput
    data object Hide : SyncInput

    /** Asked for a round soon: a local change, a tap on the icon, a message about the open band. */
    data object Wake : SyncInput

    /** A round ended with [status]; [waiting]: something still waits to be sent (for a drain). */
    data class RoundDone(val status: SyncStatus, val waiting: Boolean) : SyncInput
    data object SignedIn : SyncInput
}

/** What the scheduler does after a [reduce]. */
sealed interface Effect {
    /** Run a round now. */
    data object RunRound : Effect

    /** Wait [after] ms, then run a round; [wakeable]: a [SyncInput.Wake] asked for meanwhile ends the wait early. */
    data class WaitThenRun(val after: Long, val wakeable: Boolean) : Effect

    /** Cut short a round under way and a wait. */
    data object Cancel : Effect
    data object None : Effect
}

/**
 * What the loop's rules need from outside: whether something waits to go when a round would start, whether the
 * server sends messages (then news is sought only every [messagedInterval]), and the plain [interval] otherwise.
 */
data class SyncRules(val sending: Boolean, val messaged: Boolean, val interval: Long, val messagedInterval: Long) {
    /** Until the next round after one that went [ok] (else [backoff]). */
    fun waitAfter(ok: Boolean, backoff: Long): Long = if (!ok) {
        backoff
    } else if (messaged) {
        messagedInterval
    } else {
        interval
    }
}

/** The backoff after a failed round: 5 s, doubling, at most five minutes. */
fun nextBackoff(backoff: Long): Long = (if (backoff == 0L) 5_000 else backoff * 2).coerceAtMost(5 * 60_000)

/** The loop's rules, pure: the state after [input], and what to do. */
fun reduce(state: SyncState, input: SyncInput, rules: SyncRules): Pair<SyncState, Effect> = when (input) {
    SyncInput.Stop -> SyncState.Stopped to Effect.Cancel
    SyncInput.Start -> start(state)
    SyncInput.Hide -> hide(state, rules)
    SyncInput.Wake -> wake(state)
    SyncInput.SignedIn -> signedIn(state)
    is SyncInput.RoundDone -> roundDone(state, input, rules)
}

private fun round(backoff: Long = 0) = SyncState.Running(backoff) to Effect.RunRound

private fun drainRound(backoff: Long) = SyncState.Draining(backoff, running = true) to Effect.RunRound

/** Idempotent while the loop runs: on screen again after a drain (or after sign-in), a round goes at once. */
private fun start(state: SyncState) = when (state) {
    // Remembered for when the user acts: the app is on screen, so the loop is wanted then.
    is SyncState.Blocked -> state.copy(loopWanted = true) to Effect.None

    is SyncState.Running, is SyncState.Idle -> state to Effect.None

    // Back on screen while the drain's round still runs: that round is the loop's first.
    is SyncState.Draining -> if (state.running) SyncState.Running() to Effect.None else round()

    SyncState.Stopped -> round()
}

/** One round at a time: a wake while one runs is not lost, the loop looks again right after it anyway. */
private fun wake(state: SyncState) = when (state) {
    is SyncState.Idle -> round(state.backoff)
    is SyncState.Draining -> if (state.running) state to Effect.None else drainRound(state.backoff)
    else -> state to Effect.None
}

/** Signing in again leaves "sign in again"; an outdated app stays blocked until it is updated (which restarts it). */
private fun signedIn(state: SyncState) = when (state) {
    is SyncState.Blocked -> when {
        state.why != SyncStatus.NEEDS_SIGN_IN -> state to Effect.None
        state.loopWanted -> round()
        else -> SyncState.Stopped to Effect.None
    }

    is SyncState.Idle -> round(state.backoff)

    else -> state to Effect.None
}

/** The web tab was hidden: send what waits (a round under way finishes first), then stop. */
private fun hide(state: SyncState, rules: SyncRules) = when (state) {
    is SyncState.Blocked -> state.copy(loopWanted = false) to Effect.None

    is SyncState.Running -> SyncState.Draining(DRAIN_BACKOFF, running = true) to Effect.None

    // Also when the loop never ran: what waits goes before the tab is forgotten.
    is SyncState.Idle, SyncState.Stopped -> if (rules.sending) drainRound(DRAIN_BACKOFF) else SyncState.Stopped to Effect.Cancel

    is SyncState.Draining -> state to Effect.None
}

private fun roundDone(state: SyncState, done: SyncInput.RoundDone, rules: SyncRules): Pair<SyncState, Effect> {
    val blocked = done.status == SyncStatus.NEEDS_SIGN_IN || done.status == SyncStatus.UPDATE_REQUIRED
    val ok = done.status == SyncStatus.SYNCED
    return when (state) {
        is SyncState.Draining -> when {
            blocked -> SyncState.Blocked(done.status, loopWanted = false) to Effect.None

            !done.waiting -> SyncState.Stopped to Effect.None

            // Soon again while it goes well, else with backoff while offline; nothing hurries a hidden tab.
            else -> SyncState.Draining(if (ok) DRAIN_BACKOFF else nextBackoff(state.backoff), running = false) to
                Effect.WaitThenRun(if (ok) DRAIN_PAUSE else state.backoff, wakeable = false)
        }

        is SyncState.Running -> when {
            blocked -> SyncState.Blocked(done.status, loopWanted = true) to Effect.None

            // A failed round doubles the wait, from 5 s; a good one resets it.
            else -> {
                val backoff = if (ok) 0L else nextBackoff(state.backoff)
                SyncState.Idle(backoff) to Effect.WaitThenRun(rules.waitAfter(ok, backoff), wakeable = true)
            }
        }

        // A round run by hand (not the loop's): only what blocks every round counts; the loop goes on if it was between rounds.
        else -> when {
            blocked -> SyncState.Blocked(done.status, loopWanted = state is SyncState.Idle) to Effect.None
            else -> state to Effect.None
        }
    }
}

/** A drain's first backoff while offline, and the pause between rounds that go well. */
private const val DRAIN_BACKOFF = 5_000L
private const val DRAIN_PAUSE = 2_000L
