package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.domain.Settlement
import com.jacekgajek.artsplit.domain.SettlementCalculator
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Guest
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.Rejection
import kotlinx.datetime.LocalDate
import kotlin.test.fail

fun pln(text: String) = Money.parse(text)

val DATE = LocalDate(2026, 9, 13)

/** Builds band state through the real [Engine], so tests exercise reducer and permissions together. */
class Scenario(vararg memberNames: String) {
    private var nextOp = 0

    var band: Band = Band.create(
        id = "band",
        name = "Test band",
        currency = "PLN",
        ownerMemberId = memberNames.first().lowercase(),
        ownerName = memberNames.first(),
        ownerUserId = "user-${memberNames.first().lowercase()}",
    )
        private set

    init {
        memberNames.drop(1).forEach { name -> apply(AddMember(name.lowercase(), name)) }
    }

    fun apply(op: Operation, actor: Actor = Actor.System): Scenario {
        when (val result = Engine.apply(band, actor, op, "op${nextOp++}", nextOp.toLong())) {
            is ApplyResult.Applied -> band = result.band
            is ApplyResult.Rejected -> fail("Expected $op to be applied but it was rejected: ${result.rejection}")
        }
        return this
    }

    fun rejected(op: Operation, actor: Actor = Actor.System): Rejection =
        when (val result = Engine.apply(band, actor, op, "op${nextOp++}", nextOp.toLong())) {
            is ApplyResult.Applied -> fail("Expected $op to be rejected but it was applied")
            is ApplyResult.Rejected -> result.rejection
        }

    fun event(id: Id, vararg participants: String, guests: List<Pair<String, Int>> = emptyList()): Scenario = apply(
        CreateEvent(
            eventId = id,
            name = id,
            date = DATE,
            guests = guests.map { Guest(it.first.lowercase(), it.first) },
            participants = participants.map { Participant(it.lowercase()) } +
                guests.map { Participant(it.first.lowercase(), it.second) },
        ),
    )

    /** Links a user to the member and returns that user as an actor. */
    fun userFor(memberId: Id, role: Role): Actor.User {
        val existing = band.member(memberId)?.userId
        if (existing != null) return Actor.User(existing)
        apply(LinkUser(memberId, "user-$memberId", role))
        return Actor.User("user-$memberId")
    }

    fun settle(eventId: Id): Settlement = SettlementCalculator.settle(band, band.event(eventId)!!)
}

/** Targets (what each party should end up with), keyed by party id. */
val Settlement.targets: Map<Id, Money> get() = lines.associate { it.partyId to it.target }
