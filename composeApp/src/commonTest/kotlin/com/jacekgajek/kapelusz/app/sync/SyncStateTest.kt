package com.jacekgajek.kapelusz.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** The sync loop's rules as a table ([reduce]): where it goes on each input, and what it does next. */
class SyncStateTest {
    private val interval = 30_000L
    private val messagedInterval = 300_000L

    private fun step(state: SyncState, input: SyncInput, sending: Boolean = false, messaged: Boolean = false): Pair<SyncState, Effect> {
        val rules = SyncRules(sending, messaged, interval, messagedInterval)
        return reduce(state, input, rules)
    }

    private fun running(backoff: Long = 0) = SyncState.Running(backoff) to Effect.RunRound

    private fun idle(backoff: Long, wait: Long) = SyncState.Idle(backoff) to Effect.WaitThenRun(wait, wakeable = true)

    private fun <S : SyncState> unchanged(state: S) = state to Effect.None

    @Test
    fun startingRunsARoundAndAGoodOneWaitsTheInterval() {
        assertEquals(running(), step(SyncState.Stopped, SyncInput.Start))
        val done = SyncInput.RoundDone(SyncStatus.SYNCED, waiting = false)
        assertEquals(idle(0, interval), step(SyncState.Running(), done))
        assertEquals(idle(0, messagedInterval), step(SyncState.Running(), done, messaged = true))
        assertEquals(unchanged(SyncState.Idle(0)), step(SyncState.Idle(0), SyncInput.Start), "started again: the loop goes on as it was")
        assertEquals(unchanged(SyncState.Idle(40_000)), step(SyncState.Idle(40_000), SyncInput.Start), "and keeps its backoff")
        assertEquals(unchanged(SyncState.Running()), step(SyncState.Running(), SyncInput.Start))
    }

    @Test
    fun aFailedRoundWaitsFiveSecondsThenDoublesUpToFiveMinutes() {
        val failed = SyncInput.RoundDone(SyncStatus.OFFLINE, waiting = true)
        var state: SyncState = SyncState.Running()
        val waits = mutableListOf<Long>()
        repeat(8) {
            val (next, effect) = step(state, failed)
            waits += (effect as Effect.WaitThenRun).after
            state = step(next, SyncInput.Wake).first
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), waits)
        assertEquals(idle(0, interval), step(state, SyncInput.RoundDone(SyncStatus.SYNCED, false)), "a good one resets")
    }

    @Test
    fun aWakeRunsOnlyBetweenRounds() {
        assertEquals(running(backoff = 10_000), step(SyncState.Idle(10_000), SyncInput.Wake))
        assertEquals(unchanged(SyncState.Running()), step(SyncState.Running(), SyncInput.Wake), "one at a time")
        assertEquals(unchanged(SyncState.Stopped), step(SyncState.Stopped, SyncInput.Wake), "not off screen")
        val blocked = SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true)
        assertEquals(unchanged(blocked), step(blocked, SyncInput.Wake), "retrying would only repeat silent renewals")
    }

    @Test
    fun signingInAgainUnblocksButAnOutdatedAppStaysBlocked() {
        val needsSignIn = SyncInput.RoundDone(SyncStatus.NEEDS_SIGN_IN, waiting = true)
        assertEquals(unchanged(SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true)), step(SyncState.Running(), needsSignIn))
        assertEquals(running(), step(SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true), SyncInput.SignedIn))
        assertEquals(running(10_000), step(SyncState.Idle(10_000), SyncInput.SignedIn), "a new account syncs at once")
        assertEquals(unchanged(SyncState.Running()), step(SyncState.Running(), SyncInput.SignedIn), "the round under way sees it")
        val outdated = SyncState.Blocked(SyncStatus.UPDATE_REQUIRED, loopWanted = true)
        assertEquals(unchanged(outdated), step(outdated, SyncInput.SignedIn))
        assertEquals(unchanged(outdated), step(outdated, SyncInput.Start))
    }

    @Test
    fun aHiddenTabSendsWhatWaitsThenStops() {
        assertEquals(SyncState.Stopped to Effect.Cancel, step(SyncState.Idle(0), SyncInput.Hide), "nothing waits: nothing to send")
        val draining = SyncState.Draining(5_000, running = true)
        assertEquals(draining to Effect.RunRound, step(SyncState.Idle(0), SyncInput.Hide, sending = true))
        assertEquals(draining to Effect.RunRound, step(SyncState.Stopped, SyncInput.Hide, sending = true), "loop or not")
        assertEquals(unchanged(draining), step(SyncState.Running(), SyncInput.Hide), "the round under way finishes first")
        assertEquals(unchanged(draining), step(draining, SyncInput.Wake), "one at a time here too")
        val sent = SyncInput.RoundDone(SyncStatus.SYNCED, waiting = true)
        val pause = Effect.WaitThenRun(2_000, wakeable = false)
        assertEquals(SyncState.Draining(5_000, running = false) to pause, step(draining, sent))
        val offline = SyncInput.RoundDone(SyncStatus.OFFLINE, waiting = true)
        val backoff = Effect.WaitThenRun(5_000, wakeable = false)
        assertEquals(SyncState.Draining(10_000, running = false) to backoff, step(draining, offline), "nothing hurries a hidden tab")
        assertEquals(unchanged(SyncState.Stopped), step(draining, SyncInput.RoundDone(SyncStatus.SYNCED, waiting = false)))
        val blocked = SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = false)
        assertEquals(unchanged(blocked), step(draining, SyncInput.RoundDone(SyncStatus.NEEDS_SIGN_IN, true)), "guards hold while hidden")
        val waiting = SyncState.Draining(5_000, running = false)
        assertEquals(draining to Effect.RunRound, step(waiting, SyncInput.Wake), "the drain goes on")
    }

    @Test
    fun aRoundRunByHandBlocksTheLoopTooWhenItMust() {
        val outdated = SyncInput.RoundDone(SyncStatus.UPDATE_REQUIRED, waiting = false)
        assertEquals(unchanged(SyncState.Blocked(SyncStatus.UPDATE_REQUIRED, loopWanted = false)), step(SyncState.Stopped, outdated))
        val byHand = SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = false)
        assertEquals(unchanged(byHand), step(SyncState.Stopped, SyncInput.RoundDone(SyncStatus.NEEDS_SIGN_IN, false)))
        assertEquals(unchanged(SyncState.Stopped), step(byHand, SyncInput.SignedIn), "no loop was running: none starts")
        assertEquals(unchanged(byHand.copy(loopWanted = true)), step(byHand, SyncInput.Start), "wanted once the user signs in")
        assertEquals(unchanged(SyncState.Idle(0)), step(SyncState.Idle(0), SyncInput.RoundDone(SyncStatus.OFFLINE, true)))
        val draining = SyncState.Draining(5_000, running = true)
        assertEquals(unchanged(SyncState.Running()), step(draining, SyncInput.Start), "the drain's round becomes the loop's")
        assertEquals(running(), step(SyncState.Draining(5_000, running = false), SyncInput.Start))
    }

    @Test
    fun stopEndsEverything() {
        val states = listOf(
            SyncState.Idle(0),
            SyncState.Running(),
            SyncState.Draining(5_000, true),
            SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true),
        )
        for (state in states) assertEquals(SyncState.Stopped to Effect.Cancel, step(state, SyncInput.Stop))
    }
}
