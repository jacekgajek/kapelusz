package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.material.icons.filled.PhoneAndroid
import com.jacekgajek.kapelusz.app.generated.blik_mine
import com.jacekgajek.kapelusz.app.generated.blik_mine_off
import com.jacekgajek.kapelusz.domain.BlikPhone
import androidx.compose.foundation.BorderStroke
import com.jacekgajek.kapelusz.app.generated.support_me
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.jacekgajek.kapelusz.app.platform.rememberUpdateControls
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
    otherMembers: Int,
    autoUpdate: Boolean,
    onAutoUpdate: (Boolean) -> Unit,
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

            Column(Modifier.padding(horizontal = 16.dp)) {
                rememberUpdateControls()?.let { AppUpdatesSection(it, autoUpdate, onAutoUpdate) }
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
            }
            // Set apart from the account actions above.
            Spacer(Modifier.height(16.dp))
            val uriHandler = LocalUriHandler.current
            SettingsItem(Icons.Default.VolunteerActivism, stringResource(Res.string.support_me), SUPPORT_URL.removePrefix("https://")) {
                uriHandler.openUri(SUPPORT_URL)
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                // Last, set apart: it cannot be undone (ITERATION_2 §7).
                if (onDeleteBand != null) {
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    var deleting by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { deleting = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 16.dp),
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
