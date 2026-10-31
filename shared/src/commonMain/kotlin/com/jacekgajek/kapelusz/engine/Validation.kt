package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.SharePercent
import com.jacekgajek.kapelusz.rules.RoleChanges
import com.jacekgajek.kapelusz.rules.Viewers
import com.jacekgajek.kapelusz.text.BankAccount
import com.jacekgajek.kapelusz.view.BandView

// Validation shared by the reducers: names, amounts, shares, members, agenda. Each throws [RejectedException] through
// [invalidIf], [conflictIf] or [orNotFound]; see [Reducer].

/** A viewer only watches: they don't play in events (and so never get a share or a transfer). */
internal fun validateNotViewer(band: Band, memberId: Id) = invalidIf(band.member(memberId)?.role == Role.VIEWER, RejectionReason.VIEWER_NOT_PARTICIPANT) { "Viewers take no part in events" }

/** A member may get [role] only when nothing they hold or play in stands in the way; see [RoleChanges]. */
internal fun validateRoleChange(band: Band, memberId: Id, role: Role) {
    RoleChanges.refusal(BandView(band), memberId, role)?.let { reason ->
        reject(RejectionCode.CONFLICT, reason, "Settle their events or hand over the band fund first")
    }
}

internal fun validateActiveMember(band: Band, memberId: Id) {
    val member = band.member(memberId).orNotFound { "Member $memberId" }
    invalidIf(member.archived, RejectionReason.ARCHIVED_MEMBER) { "Member ${member.name} is archived" }
}

internal fun validateWeight(weight: Int) = invalidIf(weight !in 0..10_000, RejectionReason.INVALID_SHARE) { "Share weight must be between 0 and 10000" }

/** A share as stored (I7 §6): a weight, a fixed amount or a percentage of the profit. */
internal data class Share(val weight: Int, val fixed: Money?, val percent: Int?) {
    val byWeight: Boolean get() = fixed == null && percent == null

    fun applyTo(participant: Participant) = participant.copy(weight = weight, fixedShare = fixed, percent = percent)
}

/** Checked; at most one of [fixed] and [percent]. 0% is no share at all: a weight of 0. */
internal fun validShare(weight: Int, fixed: Money?, percent: Int?): Share {
    validateWeight(weight)
    fixed?.let { validatePositive(it, "Fixed share") }
    percent?.let { invalidIf(it !in 0..SharePercent.MAX, RejectionReason.INVALID_SHARE) { "A percentage must be between 0 and ${SharePercent.MAX}" } }
    invalidIf(fixed != null && percent != null, RejectionReason.INVALID_INPUT) { "A share is a fixed amount or a percentage, not both" }
    return if (percent == 0) Share(0, null, null) else Share(weight, fixed, percent)
}

internal fun validatePositive(amount: Money, what: String) = invalidIf(!amount.isPositive, RejectionReason.INVALID_AMOUNT) { "$what must be positive" }

internal fun String.validName(what: String): String = trim().also { invalidIf(it.isEmpty(), RejectionReason.EMPTY_NAME) { "$what cannot be empty" } }

internal fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** A fund cut as stored: exactly one of a positive amount or a percentage of 1–100; nothing at all is null. */
internal fun validFundCut(cut: FundCut): FundCut? {
    invalidIf(cut.amount != null && cut.percent != null, RejectionReason.INVALID_INPUT) { "Either an amount or a percentage" }
    cut.percent?.let(::validateFundPercent)
    cut.amount?.let { validatePositive(it, "Band fund amount") }
    return cut.takeUnless { it.amount == null && (it.percent ?: 0) == 0 }
}

internal fun validateFundPercent(percent: Int) = invalidIf(percent !in 0..FundCut.MAX_PERCENT, RejectionReason.INVALID_SHARE) { "A percentage must be between 0 and ${FundCut.MAX_PERCENT}" }

/** An account number as stored, null for none; refused if not one. */
internal fun account(input: String?): String? {
    val account = input?.let { BankAccount.normalize(it) }
    invalidIf(input != null && account == null, RejectionReason.INVALID_ACCOUNT) { "Not a bank account number" }
    return account
}

internal fun validAgenda(agenda: List<AgendaTime>): List<AgendaTime> {
    invalidIf(agenda.size > AgendaTime.MAX_TIMES, RejectionReason.INVALID_INPUT) { "At most ${AgendaTime.MAX_TIMES} agenda times" }
    invalidIf(agenda.map { it.id }.toSet().size != agenda.size, RejectionReason.DUPLICATE_ID) { "Duplicate agenda ids" }
    invalidIf(agenda.any { it.id.isBlank() || it.id.length > AgendaTime.MAX_ID }, RejectionReason.INVALID_INPUT) { "Invalid agenda id" }
    agenda.forEach { validAgendaLabel(it.kind, it.label) }
    return agenda.map { it.copy(label = it.label?.trim()) }
}

/** A built-in kind needs no label; a custom entry needs one. Any label is short. */
internal fun validAgendaLabel(kind: AgendaKind?, label: String?) {
    invalidIf(kind == null && label.isNullOrBlank(), RejectionReason.EMPTY_NAME) { "Agenda label is required" }
    invalidIf((label?.trim()?.length ?: 0) > AgendaTime.MAX_LABEL, RejectionReason.INVALID_INPUT) { "Agenda label is too long" }
}
