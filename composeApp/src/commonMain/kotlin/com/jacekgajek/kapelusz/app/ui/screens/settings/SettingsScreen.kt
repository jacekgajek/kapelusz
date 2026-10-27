package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.material.icons.filled.PhoneAndroid
import com.jacekgajek.kapelusz.app.generated.blik_mine
import com.jacekgajek.kapelusz.app.generated.blik_mine_off
import com.jacekgajek.kapelusz.domain.BlikPhone
import androidx.compose.foundation.BorderStroke
import com.jacekgajek.kapelusz.app.generated.support_me
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Policy
import com.jacekgajek.kapelusz.app.generated.privacy_policy
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import com.jacekgajek.kapelusz.app.generated.leave_band
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.account
import com.jacekgajek.kapelusz.app.generated.agenda_template_title
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.band_name
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
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.generated.settings_band
import com.jacekgajek.kapelusz.app.generated.settings_personal
import com.jacekgajek.kapelusz.app.generated.sign_out
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere
import com.jacekgajek.kapelusz.app.platform.storeListing
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.VersionLabel
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.displayLabel
import com.jacekgajek.kapelusz.app.ui.model.label
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import org.jetbrains.compose.resources.stringResource

/** The settings that open a page of their own (ITERATION_2 §8). */
enum class SettingsPage { FUND, AGENDA, CALENDAR }

private const val SUPPORT_URL = "https://buymeacoffee.com/jacekgajek"

/** The privacy policy (published by the deploy), in Polish under `/pl`. */
private const val PRIVACY_URL = "https://kapelusz.jacekgajek.com/privacy-policy"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    language: String?,
    onLanguage: (String?) -> Unit,
    account: String?,
    /** The user's Google photo (as their member in this band has it). */
    accountPicture: String?,
    onSignOut: () -> Unit,
    /** Signs out every device of the user, this one too (ITERATION_3 R6). Throws when it did not work (e.g. offline). */
    onSignOutEverywhere: suspend () -> Unit,
    bandName: String,
    onRenameBand: ((String) -> Unit)?,
    treasurer: String?,
    /** Who holds the band fund (also if they are no longer among [treasurerOptions]); null when it is off. */
    treasurerName: String?,
    treasurerOptions: List<Pair<String, String>>,
    onTreasurer: ((String) -> Unit)?,
    /** Turns the band fund off; null while it holds or owes money ([fundBalance] says how much it holds). */
    onDisableFund: (() -> Unit)?,
    fundBalance: String,
    /** Deletes the band for everyone; only for its owner. Throws when it did not work (e.g. offline). */
    onDeleteBand: (suspend () -> Unit)?,
    /** Leaves the band (not as its owner, who has [onDeleteBand]). Throws when it did not work (e.g. offline). */
    onLeaveBand: (suspend () -> Unit)?,
    /** What else leaving ends, said in its dialog (a pair settling together, the band fund held). */
    leaveWarnings: List<String>,
    /** Band members, where the owner hands the band to another leader before leaving. */
    onOpenMembers: () -> Unit,
    otherMembers: Int,
    /** The band's agenda template; [onAgendaTemplate] is null for non-leaders. */
    agendaTemplate: List<AgendaTemplateItem>,
    /** The band stored its own template (even if it equals the defaults). */
    agendaTemplateStored: Boolean,
    onAgendaTemplate: ((List<AgendaTemplateItem>) -> Unit)?,
    /** The calendar subscription (needs the server): get it, or change it. */
    calendar: suspend (CalendarSubscriptionChange?) -> CalendarSubscription,
    /** The band's raw file from the server, for the secret menu; null for non-leaders. */
    rawData: (suspend () -> String)?,
    /** The page shown: null for the main list. */
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    /** No band fund and no whole-band calendar for artists (ITERATION_4 §1.1). */
    artist: Boolean = false,
    /** Your BLIK number in this band (ITERATION_5 §8), as stored; [onBlikPhone] is null for whoever is never paid. */
    blikPhone: String? = null,
    onBlikPhone: ((String?) -> Unit)? = null,
) {
    when (page) {
        SettingsPage.FUND -> SettingsPageScaffold(stringResource(Res.string.band_fund), onBack) {
            if (artist) LaunchedEffect(Unit) { onBack() } else FundSettings(treasurer, treasurerName, treasurerOptions, onTreasurer, onDisableFund, fundBalance)
        }
        SettingsPage.AGENDA -> SettingsPageScaffold(stringResource(Res.string.agenda_template_title), onBack) {
            if (onAgendaTemplate != null) AgendaTemplateEditor(agendaTemplate, agendaTemplateStored, onAgendaTemplate)
            // No longer a leader (e.g. demoted meanwhile): nothing to edit here.
            else LaunchedEffect(Unit) { onBack() }
        }
        // The app's own notifications are gone (ITERATION_6 §9): gigs reach the phone through the calendar.
        SettingsPage.CALENDAR -> SettingsPageScaffold(stringResource(Res.string.calendar_title), onBack) {
            CalendarSection(calendar, showTitle = false, showIncludeAll = !artist)
        }
        null -> SettingsPageScaffold(stringResource(Res.string.settings), onBack, padded = false) {
            var choosingLanguage by remember { mutableStateOf(false) }
            var renaming by remember { mutableStateOf(false) }
            var editingBlik by remember { mutableStateOf(false) }
            val languages = listOf(null to Res.string.language_system, "en" to Res.string.language_en, "pl" to Res.string.language_pl)
            // Grouped by whom they affect: the band's settings change things for everyone, the personal ones only for you.
            SectionHeader(stringResource(Res.string.settings_band), Modifier.padding(horizontal = 16.dp))
            SettingsItem(Icons.Default.Groups, stringResource(Res.string.band_name), bandName, onClick = onRenameBand?.let { { renaming = true } })
            if (!artist) SettingsItem(
                Icons.Default.Savings,
                stringResource(Res.string.band_fund),
                treasurerName?.let { stringResource(Res.string.fund_summary_on, it) } ?: stringResource(Res.string.fund_summary_off),
                chevron = true,
            ) { onOpenPage(SettingsPage.FUND) }
            if (onBlikPhone != null) {
                SettingsItem(Icons.Default.PhoneAndroid, stringResource(Res.string.blik_mine), blikPhone?.let(BlikPhone::display) ?: stringResource(Res.string.blik_mine_off)) {
                    editingBlik = true
                }
            }
            if (onAgendaTemplate != null) {
                SettingsItem(Icons.Default.Schedule, stringResource(Res.string.agenda_template_title), agendaTemplate.map { it.displayLabel() }.joinToString(", "), chevron = true) {
                    onOpenPage(SettingsPage.AGENDA)
                }
            }
            SectionHeader(stringResource(Res.string.settings_personal), Modifier.padding(horizontal = 16.dp))
            SettingsItem(Icons.Default.Language, stringResource(Res.string.language), stringResource((languages.firstOrNull { it.first == language } ?: languages.first()).second)) {
                choosingLanguage = true
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
            // In the language the app shows (the parameter [language] is only a manual choice).
            val privacyUrl = if (androidx.compose.ui.text.intl.Locale.current.language == "pl") "$PRIVACY_URL/pl" else PRIVACY_URL
            SettingsItem(Icons.Default.Policy, stringResource(Res.string.privacy_policy), privacyUrl.removePrefix("https://")) {
                uriHandler.openUri(privacyUrl)
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(stringResource(Res.string.account))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    accountPicture?.let { Avatar(account.orEmpty(), it, 40.dp, decorative = true); Spacer(Modifier.width(12.dp)) }
                    Text(account.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
                // Below the address, so a long one has the whole width; they wrap when both don't fit.
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Signing out takes a moment (sync stops, data is cleared): a spinner at once, and no second tap. The
                    // screen goes away when it is done.
                    var signingOut by remember { mutableStateOf(false) }
                    OutlinedButton(enabled = !signingOut, onClick = { signingOut = true; onSignOut() }) {
                        if (signingOut) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        if (signingOut) Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.sign_out))
                    }
                    var signingOutEverywhere by remember { mutableStateOf(false) }
                    TextButton(onClick = { signingOutEverywhere = true }) {
                        Text(stringResource(Res.string.sign_out_everywhere))
                    }
                    if (signingOutEverywhere) SignOutEverywhereDialog(onSignOutEverywhere, onDismiss = { signingOutEverywhere = false })
                }
                // Red, as a link: the owner is told how to hand the band over or delete it instead.
                val deleteButton = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                if (onLeaveBand != null) {
                    var leaving by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { leaving = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(Res.string.leave_band))
                    }
                    if (leaving) {
                        // Only the owner may delete the band, and the owner alone can't leave it.
                        if (onDeleteBand != null) {
                            OwnerCannotLeaveDialog(
                                onOpenMembers = onOpenMembers,
                                onDeleteBand = { leaving = false; scope.launch { deleteButton.bringIntoView() } },
                                onDismiss = { leaving = false },
                            )
                        } else {
                            LeaveBandDialog(bandName, leaveWarnings, onLeaveBand, onDismiss = { leaving = false })
                        }
                    }
                }
                // Last, set apart: it cannot be undone (ITERATION_2 §7).
                if (onDeleteBand != null) {
                    HorizontalDivider(Modifier.padding(top = 24.dp))
                    var deleting by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { deleting = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 16.dp).bringIntoViewRequester(deleteButton),
                    ) {
                        Icon(Icons.Default.DeleteForever, null)
                        Text(stringResource(Res.string.delete_band), Modifier.padding(start = 8.dp))
                    }
                    if (deleting) DeleteBandDialog(bandName, otherMembers, onDeleteBand, onDismiss = { deleting = false })
                }
                var secretMenu by remember { mutableStateOf(false) }
                // A tap on the version opens the secret menu for leaders (ITERATION_2 §7.1); for others it is just text.
                VersionLabel(Modifier.align(Alignment.End).padding(vertical = 24.dp).then(if (rawData != null) Modifier.clickable { secretMenu = true } else Modifier))
                if (secretMenu && rawData != null) SecretMenuDialog(rawData, onDismiss = { secretMenu = false })
            }

            if (choosingLanguage) {
                AlertDialog(
                    onDismissRequest = { choosingLanguage = false },
                    title = { Text(stringResource(Res.string.language)) },
                    text = {
                        Column(Modifier.selectableGroup()) {
                            languages.forEach { (code, label) ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .selectable(selected = language == code, onClick = { onLanguage(code); choosingLanguage = false }, role = Role.RadioButton)
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = language == code, onClick = null)
                                    Text(stringResource(label), Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { choosingLanguage = false }) { Text(stringResource(Res.string.cancel)) } },
                )
            }
            if (editingBlik && onBlikPhone != null) BlikPhoneDialog(blikPhone, onBlikPhone, onDismiss = { editingBlik = false })
            if (renaming && onRenameBand != null) {
                TextInputDialog(stringResource(Res.string.band_name), stringResource(Res.string.band_name), bandName, onConfirm = { if (it != bandName) onRenameBand(it) }, onDismiss = { renaming = false })
            }
        }
    }
}
