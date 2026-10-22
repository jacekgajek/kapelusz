package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.LaunchedEffect
import com.jacekgajek.artsplit.app.generated.settings_personal
import com.jacekgajek.artsplit.app.generated.settings_band
import com.jacekgajek.artsplit.app.generated.band_fund
import com.jacekgajek.artsplit.app.generated.fund_explanation
import com.jacekgajek.artsplit.app.generated.fund_status
import com.jacekgajek.artsplit.app.generated.fund_summary_off
import com.jacekgajek.artsplit.app.generated.fund_summary_on
import com.jacekgajek.artsplit.app.generated.reminders_summary_off
import com.jacekgajek.artsplit.app.generated.reminders_summary_on
import com.jacekgajek.artsplit.app.generated.calendar_summary
import com.jacekgajek.artsplit.app.generated.notifications_and_calendar
import com.jacekgajek.artsplit.app.ui.model.displayLabel
import com.jacekgajek.artsplit.app.ui.components.TextInputDialog
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.data.nowMillis
import com.jacekgajek.artsplit.app.generated.diag_test_text
import com.jacekgajek.artsplit.app.generated.diag_test_title
import com.jacekgajek.artsplit.app.generated.diag_test_set
import com.jacekgajek.artsplit.app.generated.diag_test
import com.jacekgajek.artsplit.app.generated.diag_none_scheduled
import com.jacekgajek.artsplit.app.generated.diag_open_settings
import com.jacekgajek.artsplit.app.generated.diag_battery_optimised
import com.jacekgajek.artsplit.app.generated.diag_battery_unrestricted
import com.jacekgajek.artsplit.app.generated.diag_notifications
import com.jacekgajek.artsplit.app.generated.diag_reminders
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import com.jacekgajek.artsplit.app.platform.ReminderDiagnostics
import com.jacekgajek.artsplit.app.platform.rememberReminderDiagnostics
import androidx.compose.material3.HorizontalDivider
import com.jacekgajek.artsplit.app.generated.agenda_template_reset
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ContentCopy
import org.jetbrains.compose.resources.StringResource
import com.jacekgajek.artsplit.app.generated.secret_menu
import com.jacekgajek.artsplit.app.generated.copy_raw_data
import com.jacekgajek.artsplit.app.generated.raw_data_copied
import com.jacekgajek.artsplit.app.generated.raw_data_failed
import com.jacekgajek.artsplit.app.generated.close
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jacekgajek.artsplit.app.generated.link_copied
import com.jacekgajek.artsplit.app.generated.copy_link
import com.jacekgajek.artsplit.app.generated.calendar_failed
import com.jacekgajek.artsplit.app.generated.calendar_refresh_note
import com.jacekgajek.artsplit.app.generated.calendar_reset_text
import com.jacekgajek.artsplit.app.generated.calendar_reset_title
import com.jacekgajek.artsplit.app.generated.calendar_reset
import com.jacekgajek.artsplit.app.generated.calendar_include_all
import com.jacekgajek.artsplit.app.generated.calendar_add_google
import com.jacekgajek.artsplit.app.generated.calendar_get
import com.jacekgajek.artsplit.app.generated.calendar_hint
import com.jacekgajek.artsplit.app.generated.calendar_title
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalUriHandler
import com.jacekgajek.artsplit.app.platform.rememberCopy
import com.jacekgajek.artsplit.app.platform.isAndroidApp
import com.jacekgajek.artsplit.app.generated.calendar_add_on_computer
import com.jacekgajek.artsplit.api.CalendarSubscriptionChange
import com.jacekgajek.artsplit.api.CalendarSubscription
import com.jacekgajek.artsplit.app.generated.notifications_transfers
import com.jacekgajek.artsplit.app.generated.notifications_time
import com.jacekgajek.artsplit.app.generated.notifications_days
import com.jacekgajek.artsplit.app.generated.notifications_days_before
import com.jacekgajek.artsplit.app.generated.notifications_reminders
import com.jacekgajek.artsplit.app.generated.notifications_allow
import com.jacekgajek.artsplit.app.generated.notifications_blocked
import com.jacekgajek.artsplit.app.generated.notifications_title
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.jacekgajek.artsplit.app.ui.components.TimePickerDialog
import com.jacekgajek.artsplit.app.ui.model.formatTime
import com.jacekgajek.artsplit.app.notify.NotificationRules
import com.jacekgajek.artsplit.app.platform.rememberNotificationPermission
import com.jacekgajek.artsplit.app.platform.NotificationPermission
import com.jacekgajek.artsplit.app.data.AppSettings
import com.jacekgajek.artsplit.app.ui.model.toItem
import com.jacekgajek.artsplit.app.ui.model.TemplateDraft
import com.jacekgajek.artsplit.app.generated.move_up
import com.jacekgajek.artsplit.app.generated.agenda_add_time
import com.jacekgajek.artsplit.app.generated.agenda_template_hint
import com.jacekgajek.artsplit.app.generated.agenda_template_title
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.jacekgajek.artsplit.app.ui.components.RemoveButton
import com.jacekgajek.artsplit.app.ui.model.label
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTemplateItem
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.account
import com.jacekgajek.artsplit.app.generated.band
import com.jacekgajek.artsplit.app.generated.band_name
import com.jacekgajek.artsplit.app.generated.fund_treasurer
import com.jacekgajek.artsplit.app.generated.fund_treasurer_hint
import com.jacekgajek.artsplit.app.generated.fund_treasurer_choose
import com.jacekgajek.artsplit.app.generated.fund_enable
import com.jacekgajek.artsplit.app.generated.fund_cannot_disable
import com.jacekgajek.artsplit.app.generated.fund_disabled_hint
import com.jacekgajek.artsplit.app.generated.fund_disable_title
import com.jacekgajek.artsplit.app.generated.fund_disable_text
import com.jacekgajek.artsplit.app.generated.fund_disable_confirm
import com.jacekgajek.artsplit.app.ui.components.ConfirmDialog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.generated.language
import com.jacekgajek.artsplit.app.generated.language_en
import com.jacekgajek.artsplit.app.generated.language_pl
import com.jacekgajek.artsplit.app.generated.language_system
import com.jacekgajek.artsplit.app.generated.settings
import com.jacekgajek.artsplit.app.generated.sign_out
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.Dropdown
import com.jacekgajek.artsplit.app.ui.components.VersionLabel
import com.jacekgajek.artsplit.app.platform.UpdateControls
import com.jacekgajek.artsplit.app.platform.UpdateStatus
import com.jacekgajek.artsplit.app.platform.rememberUpdateControls
import androidx.compose.runtime.collectAsState
import com.jacekgajek.artsplit.app.generated.updates_title
import com.jacekgajek.artsplit.app.generated.updates_auto
import com.jacekgajek.artsplit.app.generated.updates_installing
import com.jacekgajek.artsplit.app.generated.updates_declined
import com.jacekgajek.artsplit.app.generated.updates_install_failed
import com.jacekgajek.artsplit.app.generated.updates_check_now
import com.jacekgajek.artsplit.app.generated.updates_checking
import com.jacekgajek.artsplit.app.generated.updates_up_to_date
import com.jacekgajek.artsplit.app.generated.updates_downloading
import com.jacekgajek.artsplit.app.generated.updates_ready
import com.jacekgajek.artsplit.app.generated.updates_install_now
import com.jacekgajek.artsplit.app.generated.updates_needs_permission
import com.jacekgajek.artsplit.app.generated.updates_allow
import com.jacekgajek.artsplit.app.generated.updates_needs_confirmation
import com.jacekgajek.artsplit.app.generated.updates_install
import com.jacekgajek.artsplit.app.generated.updates_failed
import com.jacekgajek.artsplit.app.generated.updates_unavailable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.jacekgajek.artsplit.app.generated.delete_band
import com.jacekgajek.artsplit.app.generated.delete_band_title
import com.jacekgajek.artsplit.app.generated.delete_band_everything
import com.jacekgajek.artsplit.app.generated.delete_band_members
import com.jacekgajek.artsplit.app.generated.delete_band_no_members
import com.jacekgajek.artsplit.app.generated.delete_band_unsynced
import com.jacekgajek.artsplit.app.generated.delete_band_final
import com.jacekgajek.artsplit.app.generated.delete_band_type_name
import com.jacekgajek.artsplit.app.generated.delete_band_confirm
import com.jacekgajek.artsplit.app.generated.delete_band_failed
import com.jacekgajek.artsplit.app.generated.cancel

/** The settings that open a page of their own (VERSION_2 §8). */
enum class SettingsPage { FUND, AGENDA, NOTIFICATIONS }

@Composable
fun SettingsScreen(
    language: String?,
    onLanguage: (String?) -> Unit,
    account: String?,
    onSignOut: () -> Unit,
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
    /** The band's agenda template; [onAgendaTemplate] is null for non-admins. */
    agendaTemplate: List<AgendaTemplateItem>,
    /** The band stored its own template (even if it equals the defaults). */
    agendaTemplateStored: Boolean,
    onAgendaTemplate: ((List<AgendaTemplateItem>) -> Unit)?,
    appSettings: AppSettings,
    onAppSettings: ((AppSettings) -> AppSettings) -> Unit,
    /** The calendar subscription (needs the server): get it, or change it. */
    calendar: suspend (CalendarSubscriptionChange?) -> CalendarSubscription,
    /** The band's raw file from the server, for the secret menu; null for non-admins. */
    rawData: (suspend () -> String)?,
    /** The page shown: null for the main list. */
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    val permission = rememberNotificationPermission()
    when (page) {
        SettingsPage.FUND -> SettingsPageScaffold(stringResource(Res.string.band_fund), onBack) {
            FundSettings(treasurer, treasurerName, treasurerOptions, onTreasurer, onDisableFund, fundBalance)
        }
        SettingsPage.AGENDA -> SettingsPageScaffold(stringResource(Res.string.agenda_template_title), onBack) {
            if (onAgendaTemplate != null) AgendaTemplateEditor(agendaTemplate, agendaTemplateStored, onAgendaTemplate)
            // No longer an admin (e.g. demoted meanwhile): nothing to edit here.
            else LaunchedEffect(Unit) { onBack() }
        }
        SettingsPage.NOTIFICATIONS -> SettingsPageScaffold(stringResource(notificationsTitle(permission)), onBack) {
            permission?.let { NotificationsSection(it, appSettings, onAppSettings) }
            // Without notifications (web) the page is the calendar, already named in the top bar.
            CalendarSection(calendar, showTitle = permission != null)
        }
        null -> SettingsPageScaffold(stringResource(Res.string.settings), onBack, padded = false) {
            var choosingLanguage by remember { mutableStateOf(false) }
            var renaming by remember { mutableStateOf(false) }
            val languages = listOf(null to Res.string.language_system, "en" to Res.string.language_en, "pl" to Res.string.language_pl)
            // Grouped by whom they affect: the band's settings change things for everyone, the personal ones only for you.
            SectionHeader(stringResource(Res.string.settings_band), Modifier.padding(horizontal = 16.dp))
            SettingsItem(Icons.Default.Groups, stringResource(Res.string.band_name), bandName, onClick = onRenameBand?.let { { renaming = true } })
            SettingsItem(
                Icons.Default.Savings,
                stringResource(Res.string.band_fund),
                treasurerName?.let { stringResource(Res.string.fund_summary_on, it) } ?: stringResource(Res.string.fund_summary_off),
                chevron = true,
            ) { onOpenPage(SettingsPage.FUND) }
            if (onAgendaTemplate != null) {
                SettingsItem(Icons.Default.Schedule, stringResource(Res.string.agenda_template_title), agendaTemplate.map { it.displayLabel() }.joinToString(", "), chevron = true) {
                    onOpenPage(SettingsPage.AGENDA)
                }
            }
            SectionHeader(stringResource(Res.string.settings_personal), Modifier.padding(horizontal = 16.dp))
            SettingsItem(Icons.Default.Language, stringResource(Res.string.language), stringResource(languages.first { it.first == language }.second)) {
                choosingLanguage = true
            }
            SettingsItem(
                if (permission != null) Icons.Default.Notifications else Icons.Default.Event,
                stringResource(notificationsTitle(permission)),
                stringResource(
                    when {
                        permission == null -> Res.string.calendar_summary
                        appSettings.remindersEnabled -> Res.string.reminders_summary_on
                        else -> Res.string.reminders_summary_off
                    },
                ),
                chevron = true,
            ) { onOpenPage(SettingsPage.NOTIFICATIONS) }

            Column(Modifier.padding(horizontal = 16.dp)) {
                rememberUpdateControls()?.let { AppUpdatesSection(it, autoUpdate, onAutoUpdate) }
                SectionHeader(stringResource(Res.string.account))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(account.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onSignOut) { Text(stringResource(Res.string.sign_out)) }
                }
                // Last, set apart: it cannot be undone (VERSION_2 §7).
                if (onDeleteBand != null) {
                    HorizontalDivider(Modifier.padding(top = 24.dp))
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
                // A tap on the version opens the secret menu for admins (VERSION_2 §7.1); for others it is just text.
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
            if (renaming && onRenameBand != null) {
                TextInputDialog(stringResource(Res.string.band_name), stringResource(Res.string.band_name), bandName, onConfirm = { if (it != bandName) onRenameBand(it) }, onDismiss = { renaming = false })
            }
        }
    }
}

private fun notificationsTitle(permission: NotificationPermission?): StringResource =
    if (permission != null) Res.string.notifications_and_calendar else Res.string.calendar_title

/** A settings page: the top bar and a centred column that does not stretch on wide screens. */
@Composable
private fun SettingsPageScaffold(title: String, onBack: () -> Unit, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().then(if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 8.dp)), content = content)
        }
    }
}

/** One entry of the settings list: icon, name, current value; tapping opens it (a page when [chevron]). */
@Composable
private fun SettingsItem(icon: ImageVector, title: String, summary: String?, chevron: Boolean = false, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = if (chevron) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) else null,
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    )
}

/** A heading within a settings page. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** The band fund page: what it is, whether it is on and who holds it (admins change it). */
@Composable
private fun FundSettings(
    treasurer: String?,
    treasurerName: String?,
    treasurerOptions: List<Pair<String, String>>,
    onTreasurer: ((String) -> Unit)?,
    onDisableFund: (() -> Unit)?,
    fundBalance: String,
) {
    Text(stringResource(Res.string.fund_explanation), style = MaterialTheme.typography.bodyMedium)
    val names = treasurerOptions.toMap()
    if (onTreasurer == null) {
        SectionHeader(stringResource(Res.string.fund_status))
        Text(treasurerName?.let { stringResource(Res.string.fund_summary_on, it) } ?: stringResource(Res.string.fund_summary_off))
        return
    }
    // On = someone holds the fund. Turning it on asks for the holder first; the switch shows on meanwhile.
    var enabling by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    val enabled = treasurer != null
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp)
            .toggleable(
                value = enabled || enabling,
                enabled = !enabled || onDisableFund != null,
                role = Role.Switch,
                onValueChange = { on -> if (on) enabling = true else if (enabled) confirmDisable = true else enabling = false },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.fund_enable), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled || enabling, onCheckedChange = null, enabled = !enabled || onDisableFund != null)
    }
    if (enabled || enabling) {
        Dropdown(
            treasurer,
            treasurerOptions.map { it.first },
            {
                it?.let(onTreasurer)
                enabling = false
            },
            stringResource(Res.string.fund_treasurer),
            Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { id -> id?.let { names[it] } ?: stringResource(Res.string.fund_treasurer_choose) }
        Text(stringResource(Res.string.fund_treasurer_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (enabled && onDisableFund == null) {
            Text(stringResource(Res.string.fund_cannot_disable, fundBalance), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    } else {
        Text(stringResource(Res.string.fund_disabled_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    if (confirmDisable && onDisableFund != null) {
        ConfirmDialog(
            title = stringResource(Res.string.fund_disable_title),
            text = stringResource(Res.string.fund_disable_text),
            confirmLabel = stringResource(Res.string.fund_disable_confirm),
            onConfirm = onDisableFund,
            onDismiss = { confirmDisable = false },
        )
    }
}

/** Why gig reminders might not arrive on this phone, and a test reminder (Android). */
@Composable
private fun ReminderChecks(checks: ReminderDiagnostics) {
    var testAt by remember { mutableStateOf<String?>(null) }
    val testTitle = stringResource(Res.string.diag_test_title)
    val testText = stringResource(Res.string.diag_test_text)
    val lang = language
    fun at(millis: Long): String {
        val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${formatDate(time.date, lang)} ${formatTime(time.time)}"
    }
    Text(stringResource(Res.string.diag_reminders), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    @Composable
    fun check(ok: Boolean, label: StringResource) = Text(
        (if (ok) "✓ " else "✗ ") + stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    check(checks.notificationsAllowed, Res.string.diag_notifications)
    // Information, not a fault: optimised is Android's default, and reminders arrive with it (tested on realme).
    Text(
        stringResource(if (checks.batteryUnrestricted) Res.string.diag_battery_unrestricted else Res.string.diag_battery_optimised),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = checks.openAppSettings) { Text(stringResource(Res.string.diag_open_settings)) }
    if (checks.scheduled.isEmpty()) Text(stringResource(Res.string.diag_none_scheduled), style = MaterialTheme.typography.bodySmall)
    checks.scheduled.take(5).forEach { (millis, name) -> Text("${at(millis)} · $name", style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = {
        checks.test(testTitle, testText)
        testAt = at(nowMillis() + 60_000)
    }) { Text(stringResource(Res.string.diag_test)) }
    testAt?.let { Text(stringResource(Res.string.diag_test_set, it), style = MaterialTheme.typography.bodySmall) }
}

/** Tools for admins: copy the band's raw data, e.g. to look into a problem. */
@Composable
private fun SecretMenuDialog(rawData: suspend () -> String, onDismiss: () -> Unit) {
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StringResource?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.secret_menu)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        result = runCatching { rawData() }.fold(
                            onSuccess = { if (copy(it)) Res.string.raw_data_copied else Res.string.raw_data_failed },
                            onFailure = { Res.string.raw_data_failed },
                        )
                        busy = false
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.ContentCopy, null)
                    Text(stringResource(Res.string.copy_raw_data), Modifier.padding(start = 8.dp))
                }
                result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                rememberReminderDiagnostics()?.let { ReminderChecks(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/**
 * The last step before deleting a band for everyone: every consequence spelled out, and the band's name typed out to
 * enable the (red) button, so it cannot happen by a stray tap.
 */
@Composable
private fun DeleteBandDialog(bandName: String, otherMembers: Int, onDelete: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val matches = typed.trim().equals(bandName.trim(), ignoreCase = true)
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.delete_band_title, bandName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.delete_band_everything))
                Text(
                    if (otherMembers > 0) pluralStringResource(Res.plurals.delete_band_members, otherMembers, otherMembers)
                    else stringResource(Res.string.delete_band_no_members),
                )
                Text(stringResource(Res.string.delete_band_unsynced))
                Text(stringResource(Res.string.delete_band_final), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    typed,
                    { typed = it; failed = false },
                    label = { Text(stringResource(Res.string.delete_band_type_name, bandName)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failed) Text(stringResource(Res.string.delete_band_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = matches && !working,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            onDelete()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Throwable: on the web, network errors are JsException.
                            failed = true
                        }
                        working = false
                    }
                },
            ) {
                if (working) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                Text(stringResource(Res.string.delete_band_confirm))
            }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Android: automatic updates (VERSION_2 §1): the switch, where the update stands, and what can be done about it. */
@Composable
private fun AppUpdatesSection(controls: UpdateControls, autoUpdate: Boolean, onAutoUpdate: (Boolean) -> Unit) {
    val status by controls.status.collectAsState()
    SectionHeader(stringResource(Res.string.updates_title))
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = autoUpdate, role = Role.Switch, onValueChange = onAutoUpdate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.updates_auto), Modifier.weight(1f))
        Switch(checked = autoUpdate, onCheckedChange = null)
    }
    val (message, action) = when (val s = status) {
        UpdateStatus.Idle -> null to null
        UpdateStatus.Checking -> stringResource(Res.string.updates_checking) to null
        UpdateStatus.UpToDate -> stringResource(Res.string.updates_up_to_date) to null
        is UpdateStatus.Downloading -> stringResource(Res.string.updates_downloading, s.versionName) to null
        is UpdateStatus.Ready -> stringResource(Res.string.updates_ready, s.versionName) to (Res.string.updates_install_now to controls::installNow)
        is UpdateStatus.NeedsInstallPermission -> stringResource(Res.string.updates_needs_permission, s.versionName) to (Res.string.updates_allow to controls::openInstallPermission)
        is UpdateStatus.NeedsConfirmation -> stringResource(Res.string.updates_needs_confirmation, s.versionName) to (Res.string.updates_install to controls::confirm)
        is UpdateStatus.Installing -> stringResource(Res.string.updates_installing, s.versionName) to null
        is UpdateStatus.Declined -> stringResource(Res.string.updates_declined, s.versionName) to (Res.string.updates_install_now to controls::installNow)
        is UpdateStatus.InstallFailed -> stringResource(Res.string.updates_install_failed, s.versionName) to (Res.string.updates_install_now to controls::installNow)
        UpdateStatus.Failed -> stringResource(Res.string.updates_failed) to null
        UpdateStatus.Unavailable -> stringResource(Res.string.updates_unavailable) to null
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        action?.let { (label, onClick) -> Button(onClick = onClick) { Text(stringResource(label)) } }
        if (status != UpdateStatus.Unavailable) {
            OutlinedButton(onClick = controls::checkNow, enabled = status !is UpdateStatus.Checking && status !is UpdateStatus.Downloading) {
                Text(stringResource(Res.string.updates_check_now))
            }
        }
    }
}

/**
 * The agenda times new events get (admins, VERSION_2 §4.1): rename, reorder, add, remove. Built-in entries keep
 * following the user's language until renamed. Changes apply to events created from now on.
 */
@Composable
private fun AgendaTemplateEditor(template: List<AgendaTemplateItem>, storedOwnList: Boolean, onSave: (List<AgendaTemplateItem>) -> Unit) {
    val builtInLabels = AgendaKind.entries.associateWith { it.label() }
    // Keyed on the built-in labels too: on the web the texts load asynchronously and are empty at first.
    var drafts by remember(template, builtInLabels) {
        mutableStateOf(template.map { TemplateDraft(it.kind, it.kind?.let { kind -> builtInLabels.getValue(kind) } ?: it.label.orEmpty()) })
    }
    Text(stringResource(Res.string.agenda_template_hint), style = MaterialTheme.typography.bodySmall)
    drafts.forEachIndexed { index, draft ->
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft.text,
                { value -> drafts = drafts.toMutableList().also { it[index] = draft.copy(text = value.take(AgendaTime.MAX_LABEL)) } },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = { drafts = drafts.toMutableList().also { it.add(index - 1, it.removeAt(index)) } }, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.move_up) + ": " + draft.text)
            }
            RemoveButton(draft.text) { drafts = drafts.toMutableList().also { it.removeAt(index) } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (drafts.size < AgendaTime.MAX_TIMES) TextButton(onClick = { drafts = drafts + TemplateDraft(null, "") }) { Text(stringResource(Res.string.agenda_add_time)) }
        val items = drafts.map { it.toItem(builtInLabels::getValue) }
        // Back to the built-in times; saved only with Save, as "the defaults" (null), so later built-in times apply.
        val defaults = AgendaKind.entries.map { TemplateDraft(it, builtInLabels.getValue(it)) }
        TextButton(enabled = drafts != defaults, onClick = { drafts = defaults }) { Text(stringResource(Res.string.agenda_template_reset)) }
        // A band that stored the defaults as its own list can still switch to "the defaults" (built-in times added later).
        val toDefaults = storedOwnList && drafts == defaults
        TextButton(enabled = drafts.all { it.text.isNotBlank() } && (items != template || toDefaults), onClick = { onSave(items) }) { Text(stringResource(Res.string.save)) }
    }
}

/** Android: gig reminders and transfer notifications (VERSION_2 §5.4); stored per device. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationsSection(permission: NotificationPermission, settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var pickingTime by remember { mutableStateOf(false) }
    SectionHeader(stringResource(Res.string.notifications_title))
    if (permission.needsAsking && (settings.remindersEnabled || settings.transferNotifications)) {
        Text(stringResource(Res.string.notifications_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { permission.request { granted -> if (!granted) permission.openSettings() } }) { Text(stringResource(Res.string.notifications_allow)) }
    }
    SwitchRow(stringResource(Res.string.notifications_reminders), settings.remindersEnabled) { on ->
        update { it.copy(remindersEnabled = on, notificationPromptDone = true) }
        if (on && permission.needsAsking) permission.request {}
    }
    if (settings.remindersEnabled) {
        Text(stringResource(Res.string.notifications_days_before), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationRules.LEAD_DAYS.forEach { days ->
                val selected = days in settings.reminderDays
                FilterChip(
                    selected = selected,
                    // At least one lead time stays ticked; turn reminders off with the switch instead.
                    onClick = { update { it.copy(reminderDays = if (selected && it.reminderDays.size > 1) it.reminderDays - days else it.reminderDays + days) } },
                    label = { Text(pluralStringResource(Res.plurals.notifications_days, days, days)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.notifications_time), Modifier.weight(1f))
            TextButton(onClick = { pickingTime = true }) { Text(formatTime(settings.reminderTime)) }
        }
    }
    SwitchRow(stringResource(Res.string.notifications_transfers), settings.transferNotifications) { on -> update { it.copy(transferNotifications = on) } }
    if (pickingTime) {
        TimePickerDialog(settings.reminderTime, stringResource(Res.string.notifications_time), onTime = { time -> update { it.copy(reminderTime = time) } }, onDismiss = { pickingTime = false })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * The private calendar feed (VERSION_2 §6): fetched on request (it needs the server), then copy, add to Google
 * Calendar, include the whole band, or make a new link.
 */
@Composable
private fun CalendarSection(calendar: suspend (CalendarSubscriptionChange?) -> CalendarSubscription, showTitle: Boolean = true) {
    val scope = rememberCoroutineScope()
    val copy = rememberCopy()
    val uriHandler = LocalUriHandler.current
    var subscription by remember { mutableStateOf<CalendarSubscription?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    fun run(change: CalendarSubscriptionChange?) {
        working = true
        failed = false
        scope.launch {
            try {
                subscription = calendar(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: on the web, network errors are JsException.
                failed = true
            }
            working = false
        }
    }
    if (showTitle) SectionHeader(stringResource(Res.string.calendar_title))
    Text(stringResource(Res.string.calendar_hint), style = MaterialTheme.typography.bodySmall)
    val current = subscription
    if (current == null) {
        OutlinedButton(onClick = { run(null) }, enabled = !working, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(Res.string.calendar_get)) }
    } else {
        SelectionContainer { Text(current.url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Google Calendar adds a calendar by URL only on its website; on a phone that page cannot, so the app says
            // where to add it instead. The https address (not webcal://, which Google may fetch over plain http).
            if (!isAndroidApp()) Button(onClick = { uriHandler.openUri("https://calendar.google.com/calendar/r?cid=" + urlEncode(current.url)) }) {
                Text(stringResource(Res.string.calendar_add_google))
            }
            OutlinedButton(onClick = { scope.launch { copied = copy(current.url) } }) { Text(stringResource(if (copied) Res.string.link_copied else Res.string.copy_link)) }
        }
        if (isAndroidApp()) Text(stringResource(Res.string.calendar_add_on_computer), style = MaterialTheme.typography.bodySmall)
        // Not while a change is under way: a late answer could otherwise show a link that a reset just replaced.
        SwitchRow(stringResource(Res.string.calendar_include_all), current.includeAll, enabled = !working) { on -> run(CalendarSubscriptionChange(includeAll = on)) }
        TextButton(onClick = { confirmReset = true }, enabled = !working) { Text(stringResource(Res.string.calendar_reset)) }
        Text(stringResource(Res.string.calendar_refresh_note), style = MaterialTheme.typography.bodySmall)
    }
    if (failed) Text(stringResource(Res.string.calendar_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(Res.string.calendar_reset_title),
            text = stringResource(Res.string.calendar_reset_text),
            confirmLabel = stringResource(Res.string.calendar_reset),
            onConfirm = { copied = false; run(CalendarSubscriptionChange(reset = true)) },
            onDismiss = { confirmReset = false },
        )
    }
}

/** Percent-encoding for a URL query value. */
private fun urlEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() && byte >= 0 || c in "-_.~") append(c) else append("%" + (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}
