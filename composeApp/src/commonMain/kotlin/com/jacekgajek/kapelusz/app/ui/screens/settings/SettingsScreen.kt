package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.account
import com.jacekgajek.kapelusz.app.generated.account_mine
import com.jacekgajek.kapelusz.app.generated.account_mine_hint
import com.jacekgajek.kapelusz.app.generated.agenda_template_title
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.band_name
import com.jacekgajek.kapelusz.app.generated.blik_mine
import com.jacekgajek.kapelusz.app.generated.blik_mine_hint
import com.jacekgajek.kapelusz.app.generated.blik_mine_off
import com.jacekgajek.kapelusz.app.generated.calendar_summary
import com.jacekgajek.kapelusz.app.generated.calendar_title
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.delete_band
import com.jacekgajek.kapelusz.app.generated.fund_summary_off
import com.jacekgajek.kapelusz.app.generated.fund_summary_on
import com.jacekgajek.kapelusz.app.generated.language
import com.jacekgajek.kapelusz.app.generated.language_en
import com.jacekgajek.kapelusz.app.generated.language_pl
import com.jacekgajek.kapelusz.app.generated.language_system
import com.jacekgajek.kapelusz.app.generated.leave_band
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.privacy_policy
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.generated.settings_band
import com.jacekgajek.kapelusz.app.generated.settings_member_count
import com.jacekgajek.kapelusz.app.generated.settings_personal
import com.jacekgajek.kapelusz.app.generated.sign_out
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere
import com.jacekgajek.kapelusz.app.generated.support_me
import com.jacekgajek.kapelusz.app.platform.storeListing
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.PaymentDetailDialog
import com.jacekgajek.kapelusz.app.ui.components.PaymentKind
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.UiMessage
import com.jacekgajek.kapelusz.app.ui.components.VersionLabel
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.text.displayLabel
import com.jacekgajek.kapelusz.app.ui.text.label
import com.jacekgajek.kapelusz.text.BankAccount
import com.jacekgajek.kapelusz.text.BlikPhone
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The settings that open a page of their own (ITERATION_2 §8). */
enum class SettingsPage { FUND, AGENDA, CALENDAR }

private const val SUPPORT_URL = "https://buymeacoffee.com/jacekgajek"

/** The privacy policy (published by the deploy), in Polish under `/pl`. */
private const val PRIVACY_URL = "https://kapelusz.jacekgajek.com/privacy-policy"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    screen: SettingsViewModel,
    /** The page shown: null for the main list. */
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    /** Band members, where the owner also hands the band to another leader before leaving. */
    onOpenMembers: () -> Unit,
    onBack: () -> Unit,
) {
    val band = settings.band
    val fund = settings.fund
    val personal = settings.personal
    val agenda = settings.agenda
    val artist = settings.artist
    val messages = rememberMessages()
    // A change the band refused (it may have changed meanwhile): said here, where it was made.
    LaunchedEffect(screen.refusal) {
        screen.refusal?.let {
            messages.post(UiMessage.Rejected(it.reason))
            screen.refusalShown()
        }
    }
    val dialog = screen.dialog
    // A dialog whose reason went away meanwhile (the band is to be deleted, the fund turned off, no longer a member):
    // closed, not kept for when the reason comes back.
    val offered = dialog == null || dialog.stillOffered(settings)
    LaunchedEffect(dialog, offered) { if (!offered) screen.dismiss() }
    when (page) {
        SettingsPage.FUND -> SettingsPageScaffold(stringResource(Res.string.band_fund), onBack, messages) {
            if (artist) LaunchedEffect(Unit) { onBack() } else FundSettings(fund, dialog, screen::open, screen::dismiss)
        }

        SettingsPage.AGENDA -> SettingsPageScaffold(stringResource(Res.string.agenda_template_title), onBack, messages) {
            if (agenda != null) {
                AgendaTemplateEditor(agenda.template, agenda.stored, agenda.onChange)
            } // No longer a leader (e.g. demoted meanwhile): nothing to edit here.
            else {
                LaunchedEffect(Unit) { onBack() }
            }
        }

        // The app's own notifications are gone (ITERATION_6 §9): gigs reach the phone through the calendar.
        SettingsPage.CALENDAR -> SettingsPageScaffold(stringResource(Res.string.calendar_title), onBack, messages) {
            CalendarSection(personal.calendar, showTitle = false, showIncludeAll = !artist)
        }

        null -> SettingsPageScaffold(stringResource(Res.string.settings), onBack, messages, padded = false) {
            val languages = listOf(null to Res.string.language_system, "en" to Res.string.language_en, "pl" to Res.string.language_pl)
            // Grouped by whom they affect: the band's settings change things for everyone, the personal ones only for you.
            SectionHeader(stringResource(Res.string.settings_band), Modifier.padding(horizontal = 16.dp))
            val rename = { screen.open(SettingsDialog.RENAMING) }
            SettingsItem(
                Icons.Default.Groups,
                stringResource(Res.string.band_name),
                band.name,
                onClick = rename.takeIf { band.onRename != null },
            )
            if (!artist) {
                SettingsItem(
                    Icons.Default.Savings,
                    stringResource(Res.string.band_fund),
                    fund.treasurerName?.let { stringResource(Res.string.fund_summary_on, it) } ?: stringResource(Res.string.fund_summary_off),
                    chevron = true,
                ) { onOpenPage(SettingsPage.FUND) }
            }
            if (personal.onBlikPhone != null) {
                SettingsItem(Icons.Default.PhoneAndroid, stringResource(Res.string.blik_mine), personal.blikPhone?.let(BlikPhone::display) ?: stringResource(Res.string.blik_mine_off)) {
                    screen.open(SettingsDialog.BLIK)
                }
            }
            if (personal.onBankAccount != null) {
                SettingsItem(Icons.Default.AccountBalance, stringResource(Res.string.account_mine), personal.bankAccount?.let(BankAccount::display) ?: stringResource(Res.string.blik_mine_off)) {
                    screen.open(SettingsDialog.ACCOUNT)
                }
            }
            if (agenda != null) {
                SettingsItem(Icons.Default.Schedule, stringResource(Res.string.agenda_template_title), agenda.template.map { it.displayLabel() }.joinToString(", "), chevron = true) {
                    onOpenPage(SettingsPage.AGENDA)
                }
            }
            // Here too, not only in the menu: changing several things about the band stays in one place.
            SettingsItem(Icons.Default.People, stringResource(Res.string.menu_roster), pluralStringResource(Res.plurals.settings_member_count, band.memberCount, band.memberCount), chevron = true) {
                onOpenMembers()
            }
            SectionHeader(stringResource(Res.string.settings_personal), Modifier.padding(horizontal = 16.dp))
            SettingsItem(Icons.Default.Language, stringResource(Res.string.language), stringResource((languages.firstOrNull { it.first == personal.language } ?: languages.first()).second)) {
                screen.open(SettingsDialog.LANGUAGE)
            }
            SettingsItem(Icons.Default.Event, stringResource(Res.string.calendar_title), stringResource(Res.string.calendar_summary), chevron = true) {
                onOpenPage(SettingsPage.CALENDAR)
            }

            val uriHandler = LocalUriHandler.current
            // Not in the Google Play build: a tip link outside Play's billing (ITERATION_7 §1.1).
            if (storeListing() == null) {
                SettingsItem(Icons.Default.VolunteerActivism, stringResource(Res.string.support_me), SUPPORT_URL.removePrefix("https://")) {
                    uriHandler.openUri(SUPPORT_URL)
                }
            }
            // In the language the app shows (the setting's language is only a manual choice).
            val privacyUrl = if (androidx.compose.ui.text.intl.Locale.current.language == "pl") "$PRIVACY_URL/pl" else PRIVACY_URL
            SettingsItem(Icons.Default.Policy, stringResource(Res.string.privacy_policy), privacyUrl.removePrefix("https://")) {
                uriHandler.openUri(privacyUrl)
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(stringResource(Res.string.account))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    personal.picture?.let {
                        Avatar(personal.account.orEmpty(), it, 40.dp, decorative = true)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(personal.account.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
                // Below the address, so a long one has the whole width; they wrap when both don't fit.
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Signing out takes a moment (sync stops, data is cleared): a spinner at once, and no second tap. The
                    // screen goes away when it is done.
                    val signingOut = screen.signingOut
                    OutlinedButton(enabled = !signingOut, onClick = { screen.signOut(personal.onSignOut) }) {
                        if (signingOut) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        if (signingOut) Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.sign_out))
                    }
                    TextButton(onClick = { screen.open(SettingsDialog.SIGN_OUT_EVERYWHERE) }) {
                        Text(stringResource(Res.string.sign_out_everywhere))
                    }
                    if (dialog == SettingsDialog.SIGN_OUT_EVERYWHERE) {
                        SignOutEverywhereDialog(personal.onSignOutEverywhere, onDismiss = screen::dismiss)
                    }
                }
                // Red, as a link: the owner is told how to hand the band over or delete it instead.
                val deleteButton = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                if (band.onLeave != null) {
                    TextButton(
                        onClick = { screen.open(SettingsDialog.LEAVING) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(Res.string.leave_band))
                    }
                    if (dialog == SettingsDialog.LEAVING) {
                        // Only the owner may delete the band, and the owner alone can't leave it.
                        if (band.onDelete != null) {
                            OwnerCannotLeaveDialog(
                                onOpenMembers = onOpenMembers,
                                onDeleteBand = {
                                    screen.dismiss()
                                    scope.launch { deleteButton.bringIntoView() }
                                },
                                onDismiss = screen::dismiss,
                            )
                        } else {
                            LeaveBandDialog(band.name, band.leaveWarnings, band.onLeave, onDismiss = screen::dismiss)
                        }
                    }
                }
                // Last, set apart: it cannot be undone (ITERATION_2 §7); asked for already, when it goes and Keep.
                if (band.deletionNotice != null) {
                    HorizontalDivider(Modifier.padding(top = 24.dp))
                    Box(Modifier.padding(top = 16.dp).bringIntoViewRequester(deleteButton)) { band.deletionNotice() }
                } else if (band.onDelete != null) {
                    HorizontalDivider(Modifier.padding(top = 24.dp))
                    OutlinedButton(
                        onClick = { screen.open(SettingsDialog.DELETING) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 16.dp).bringIntoViewRequester(deleteButton),
                    ) {
                        Icon(Icons.Default.DeleteForever, null)
                        Text(stringResource(Res.string.delete_band), Modifier.padding(start = 8.dp))
                    }
                    if (dialog == SettingsDialog.DELETING) {
                        DeleteBandDialog(band.name, band.otherMembers, band.onDelete, onDismiss = screen::dismiss)
                    }
                }
                // A tap on the version opens the secret menu for leaders (ITERATION_2 §7.1); for others it is just text.
                val secret = if (band.rawData != null) Modifier.clickable { screen.open(SettingsDialog.SECRET_MENU) } else Modifier
                VersionLabel(Modifier.align(Alignment.End).padding(vertical = 24.dp).then(secret))
                if (dialog == SettingsDialog.SECRET_MENU && band.rawData != null) {
                    SecretMenuDialog(band.rawData, onDismiss = screen::dismiss)
                }
            }

            if (dialog == SettingsDialog.LANGUAGE) {
                AlertDialog(
                    onDismissRequest = screen::dismiss,
                    title = { Text(stringResource(Res.string.language)) },
                    text = {
                        Column(Modifier.selectableGroup()) {
                            languages.forEach { (code, label) ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .selectable(selected = personal.language == code, onClick = {
                                            personal.onLanguage(code)
                                            screen.dismiss()
                                        }, role = Role.RadioButton)
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = personal.language == code, onClick = null)
                                    Text(stringResource(label), Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = screen::dismiss) { Text(stringResource(Res.string.cancel)) } },
                )
            }
            if (dialog == SettingsDialog.BLIK && personal.onBlikPhone != null) {
                PaymentDetailDialog(
                    PaymentKind.BLIK,
                    stringResource(Res.string.blik_mine),
                    stringResource(Res.string.blik_mine_hint),
                    personal.blikPhone,
                    personal.onBlikPhone,
                    onDismiss = screen::dismiss,
                )
            }
            if (dialog == SettingsDialog.ACCOUNT && personal.onBankAccount != null) {
                PaymentDetailDialog(
                    PaymentKind.ACCOUNT,
                    stringResource(Res.string.account_mine),
                    stringResource(Res.string.account_mine_hint),
                    personal.bankAccount,
                    personal.onBankAccount,
                    onDismiss = screen::dismiss,
                )
            }
            if (dialog == SettingsDialog.RENAMING && band.onRename != null) {
                val title = stringResource(Res.string.band_name)
                TextInputDialog(
                    title,
                    title,
                    band.name,
                    onConfirm = { if (it != band.name) band.onRename(it) },
                    onDismiss = screen::dismiss,
                )
            }
        }
    }
}

/** Whether what the dialog changes is still on offer, as the settings are now. */
private fun SettingsDialog.stillOffered(settings: Settings): Boolean = when (this) {
    SettingsDialog.LANGUAGE, SettingsDialog.SIGN_OUT_EVERYWHERE -> true
    SettingsDialog.RENAMING -> settings.band.onRename != null
    SettingsDialog.BLIK -> settings.personal.onBlikPhone != null
    SettingsDialog.ACCOUNT -> settings.personal.onBankAccount != null
    SettingsDialog.LEAVING -> settings.band.onLeave != null
    SettingsDialog.DELETING -> settings.band.onDelete != null && settings.band.deletionNotice == null
    SettingsDialog.SECRET_MENU -> settings.band.rawData != null
    SettingsDialog.FUND_DISABLING -> settings.fund.onDisable != null
    SettingsDialog.FUND_DEFAULT -> settings.fund.treasurer != null && settings.fund.defaultSetting != null
}
