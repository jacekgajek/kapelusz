package com.jacekgajek.kapelusz.app.sync

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** The sync loop's rules as a table ([reduce]): where it goes on each input, and what it does next. */
class SyncStateTest :
    ExpectSpec({
        val interval = 30_000L
        val messagedInterval = 300_000L

        fun step(state: SyncState, input: SyncInput, sending: Boolean = false, messaged: Boolean = false): Pair<SyncState, Effect> {
            val rules = SyncRules(sending, messaged, interval, messagedInterval)
            return reduce(state, input, rules)
        }

        fun running(backoff: Long = 0) = SyncState.Running(backoff) to Effect.RunRound

        fun idle(backoff: Long, wait: Long) = SyncState.Idle(backoff) to Effect.WaitThenRun(wait, wakeable = true)

        fun <S : SyncState> unchanged(state: S) = state to Effect.None

        expect("starting runs a round and a good one waits the interval") {
            step(SyncState.Stopped, SyncInput.Start) shouldBe running()
            val done = SyncInput.RoundDone(SyncStatus.SYNCED, waiting = false)
            step(SyncState.Running(), done) shouldBe idle(0, interval)
            step(SyncState.Running(), done, messaged = true) shouldBe idle(0, messagedInterval)
            withClue("started again: the loop goes on as it was") {
                step(SyncState.Idle(0), SyncInput.Start) shouldBe unchanged(SyncState.Idle(0))
            }
            withClue("and keeps its backoff") { step(SyncState.Idle(40_000), SyncInput.Start) shouldBe unchanged(SyncState.Idle(40_000)) }
            step(SyncState.Running(), SyncInput.Start) shouldBe unchanged(SyncState.Running())
        }

        expect("a failed round waits five seconds then doubles up to five minutes") {
            val failed = SyncInput.RoundDone(SyncStatus.OFFLINE, waiting = true)
            var state: SyncState = SyncState.Running()
            val waits = mutableListOf<Long>()
            repeat(8) {
                val (next, effect) = step(state, failed)
                waits += (effect as Effect.WaitThenRun).after
                state = step(next, SyncInput.Wake).first
            }
            waits shouldBe listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L)
            withClue("a good one resets") { step(state, SyncInput.RoundDone(SyncStatus.SYNCED, false)) shouldBe idle(0, interval) }
        }

        expect("a wake runs only between rounds") {
            step(SyncState.Idle(10_000), SyncInput.Wake) shouldBe running(backoff = 10_000)
            withClue("one at a time") { step(SyncState.Running(), SyncInput.Wake) shouldBe unchanged(SyncState.Running()) }
            withClue("not off screen") { step(SyncState.Stopped, SyncInput.Wake) shouldBe unchanged(SyncState.Stopped) }
            val blocked = SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true)
            withClue("retrying would only repeat silent renewals") { step(blocked, SyncInput.Wake) shouldBe unchanged(blocked) }
        }

        expect("signing in again unblocks but an outdated app stays blocked") {
            val needsSignIn = SyncInput.RoundDone(SyncStatus.NEEDS_SIGN_IN, waiting = true)
            step(SyncState.Running(), needsSignIn) shouldBe unchanged(SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true))
            step(SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true), SyncInput.SignedIn) shouldBe running()
            withClue("a new account syncs at once") { step(SyncState.Idle(10_000), SyncInput.SignedIn) shouldBe running(10_000) }
            withClue("the round under way sees it") {
                step(SyncState.Running(), SyncInput.SignedIn) shouldBe unchanged(SyncState.Running())
            }
            val outdated = SyncState.Blocked(SyncStatus.UPDATE_REQUIRED, loopWanted = true)
            step(outdated, SyncInput.SignedIn) shouldBe unchanged(outdated)
            step(outdated, SyncInput.Start) shouldBe unchanged(outdated)
        }

        expect("a hidden tab sends what waits then stops") {
            withClue("nothing waits: nothing to send") {
                step(SyncState.Idle(0), SyncInput.Hide) shouldBe (SyncState.Stopped to Effect.Cancel)
            }
            val draining = SyncState.Draining(5_000, running = true)
            step(SyncState.Idle(0), SyncInput.Hide, sending = true) shouldBe (draining to Effect.RunRound)
            withClue("loop or not") { step(SyncState.Stopped, SyncInput.Hide, sending = true) shouldBe (draining to Effect.RunRound) }
            withClue("the round under way finishes first") { step(SyncState.Running(), SyncInput.Hide) shouldBe unchanged(draining) }
            withClue("one at a time here too") { step(draining, SyncInput.Wake) shouldBe unchanged(draining) }
            val sent = SyncInput.RoundDone(SyncStatus.SYNCED, waiting = true)
            val pause = Effect.WaitThenRun(2_000, wakeable = false)
            step(draining, sent) shouldBe (SyncState.Draining(5_000, running = false) to pause)
            val offline = SyncInput.RoundDone(SyncStatus.OFFLINE, waiting = true)
            val backoff = Effect.WaitThenRun(5_000, wakeable = false)
            withClue("nothing hurries a hidden tab") {
                step(draining, offline) shouldBe (SyncState.Draining(10_000, running = false) to backoff)
            }
            step(draining, SyncInput.RoundDone(SyncStatus.SYNCED, waiting = false)) shouldBe unchanged(SyncState.Stopped)
            val blocked = SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = false)
            withClue("guards hold while hidden") {
                step(draining, SyncInput.RoundDone(SyncStatus.NEEDS_SIGN_IN, true)) shouldBe unchanged(blocked)
            }
            val waiting = SyncState.Draining(5_000, running = false)
            withClue("the drain goes on") { step(waiting, SyncInput.Wake) shouldBe (draining to Effect.RunRound) }
        }

        expect("a round run by hand blocks the loop too when it must") {
            val outdated = SyncInput.RoundDone(SyncStatus.UPDATE_REQUIRED, waiting = false)
            step(SyncState.Stopped, outdated) shouldBe unchanged(SyncState.Blocked(SyncStatus.UPDATE_REQUIRED, loopWanted = false))
            val byHand = SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = false)
            step(SyncState.Stopped, SyncInput.RoundDone(SyncStatus.NEEDS_SIGN_IN, false)) shouldBe unchanged(byHand)
            withClue("no loop was running: none starts") { step(byHand, SyncInput.SignedIn) shouldBe unchanged(SyncState.Stopped) }
            withClue("wanted once the user signs in") { step(byHand, SyncInput.Start) shouldBe unchanged(byHand.copy(loopWanted = true)) }
            step(SyncState.Idle(0), SyncInput.RoundDone(SyncStatus.OFFLINE, true)) shouldBe unchanged(SyncState.Idle(0))
            val draining = SyncState.Draining(5_000, running = true)
            withClue("the drain's round becomes the loop's") { step(draining, SyncInput.Start) shouldBe unchanged(SyncState.Running()) }
            step(SyncState.Draining(5_000, running = false), SyncInput.Start) shouldBe running()
        }

        expect("stop ends everything") {
            val states = listOf(
                SyncState.Idle(0),
                SyncState.Running(),
                SyncState.Draining(5_000, true),
                SyncState.Blocked(SyncStatus.NEEDS_SIGN_IN, loopWanted = true),
            )
            for (state in states) step(state, SyncInput.Stop) shouldBe (SyncState.Stopped to Effect.Cancel)
        }
    })
