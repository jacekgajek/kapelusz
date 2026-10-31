package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.data.Account
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.Changes
import com.jacekgajek.kapelusz.app.data.Membership
import com.jacekgajek.kapelusz.app.data.SettingsRepository
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.leave_band_treasurer
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.ui.components.BandDeletionNotice
import com.jacekgajek.kapelusz.app.ui.components.PaymentKind
import com.jacekgajek.kapelusz.app.ui.components.fundPaymentOp
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.screens.pairEndsWarning
import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.sortedByName
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetBankAccount
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundDefault
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.rules.isArtist
import org.jetbrains.compose.resources.stringResource

/**
 * The open band's [Settings] for its user: what each setting is, and how it changes, where they may change it (the same
 * permission checks as the operations themselves). [onBandGone]: after leaving or deleting the band.
 */
@Composable
fun settingsFor(
    state: BandState.Ready,
    changes: Changes,
    account: Account,
    membership: Membership,
    api: ApiClient,
    preferences: SettingsRepository,
    onBandGone: () -> Unit,
): Settings {
    val band = state.band
    val me = state.me
    val can = Capabilities(band, state.userId)
    val session by account.session.collectAsState()
    val appSettings by preferences.settings.collectAsState()
    val stillDefault = remember(band) { BandFund.stillDefault(band).map { it.id to it.name } }

    // A refusal can't happen here: each action is offered only where it is allowed.
    fun dispatch(op: Operation) {
        changes.dispatch(op)
    }
    return Settings(
        band = BandSettings(
            name = band.name,
            onRename = { name: String -> dispatch(RenameBand(name)) }.takeIf { can.allowed(RenameBand("probe")) },
            memberCount = band.members.count { !it.archived },
            otherMembers = band.members.count { it.userId != null && it.userId != state.userId },
            onLeave = suspend {
                membership.leaveBand()
                onBandGone()
            }.takeIf { me != null },
            // Leaving unlinks the account, as a leader's Unlink does: the same pair warning as on the roster.
            leaveWarnings = me?.let { member ->
                listOfNotNull(
                    pairEndsWarning(band, member),
                    stringResource(Res.string.leave_band_treasurer).takeIf { band.treasurerMemberId == member.id },
                )
            }.orEmpty(),
            onDelete = suspend {
                membership.deleteBand()
                onBandGone()
            }.takeIf { me?.role == Role.OWNER },
            deletionNotice = band.deletion?.let {
                { BandDeletionNotice(band, { dispatch(CancelBandDeletion) }.takeIf { can.allowed(CancelBandDeletion) }) }
            },
            // The secret menu (ITERATION_2 §7.1), for the band's leaders.
            rawData = suspend { api.rawState(band.id) }.takeIf { me?.role?.atLeast(Role.LEAD) == true },
        ),
        fund = BandFundSettings(
            treasurer = band.treasurerMemberId,
            treasurerName = band.treasurerMemberId?.let { band.member(it)?.name },
            // Artists hold no money and viewers record no transfers: neither can hold the fund (V5-M1 C6a).
            treasurerOptions = band.members.filter { !it.archived && it.role != Role.ARTIST && it.role != Role.VIEWER }.sortedByName { it.name }.map { it.id to it.name },
            onTreasurer = { id: String -> dispatch(SetTreasurer(id)) }.takeIf { can.allowed(SetTreasurer("probe")) },
            onDisable = { dispatch(DisableBandFund) }.takeIf { can.allowed(DisableBandFund) && BandFund.canDisable(band) },
            balance = formatMoney(BandFund.balance(band), band.currency, language),
            default = band.fundDefault,
            defaultSetting = FundDefaultSetting(stillDefault) { cut, events -> dispatch(SetFundDefault(cut, events)) }
                .takeIf { can.allowed(SetFundDefault(null)) },
            currency = band.currency,
            blikPhone = band.fundBlikPhone,
            bankAccount = band.fundBankAccount,
            onPayment = { kind: PaymentKind, value: String? -> dispatch(fundPaymentOp(kind, value)) }.takeIf { can.allowed(SetFundBlikPhone(null)) },
        ),
        personal = PersonalSettings(
            language = appSettings.language,
            onLanguage = { code -> preferences.update { it.copy(language = code) } },
            calendar = { change -> if (change == null) api.calendar() else api.changeCalendar(change) },
            blikPhone = me?.blikPhone,
            onBlikPhone = me?.let { member -> { phone: String? -> dispatch(SetBlikPhone(member.id, phone)) }.takeIf { can.allowed(SetBlikPhone(member.id, null)) } },
            bankAccount = me?.bankAccount,
            onBankAccount = me?.let { member -> { account: String? -> dispatch(SetBankAccount(member.id, account)) }.takeIf { can.allowed(SetBankAccount(member.id, null)) } },
            account = session?.let { it.email ?: it.name },
            picture = me?.pictureUrl,
            onSignOut = account::signOutInBackground,
            onSignOutEverywhere = account::signOutEverywhere,
        ),
        agenda = AgendaSettings(band.effectiveAgendaTemplate, band.agendaTemplate != null) { items: List<AgendaTemplateItem> -> dispatch(SetAgendaTemplate(items)) }
            .takeIf { can.allowed(SetAgendaTemplate(emptyList())) },
        artist = band.isArtist(state.userId),
    )
}
