package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.calendar_add_google
import com.jacekgajek.kapelusz.app.generated.calendar_add_on_computer
import com.jacekgajek.kapelusz.app.generated.calendar_failed
import com.jacekgajek.kapelusz.app.generated.calendar_get
import com.jacekgajek.kapelusz.app.generated.calendar_hint
import com.jacekgajek.kapelusz.app.generated.calendar_include_all
import com.jacekgajek.kapelusz.app.generated.calendar_refresh_note
import com.jacekgajek.kapelusz.app.generated.calendar_reset
import com.jacekgajek.kapelusz.app.generated.calendar_reset_text
import com.jacekgajek.kapelusz.app.generated.calendar_reset_title
import com.jacekgajek.kapelusz.app.generated.calendar_title
import com.jacekgajek.kapelusz.app.generated.copy_link
import com.jacekgajek.kapelusz.app.generated.link_copied
import com.jacekgajek.kapelusz.app.platform.isAndroidApp
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The private calendar feed (ITERATION_2 §6): fetched on request (it needs the server), then copy, add to Google
 * Calendar, include the whole band, or make a new link.
 */
@Composable
internal fun CalendarSection(
    calendar: suspend (CalendarSubscriptionChange?) -> CalendarSubscription,
    showTitle: Boolean = true,
    /** Artists' feeds have only the events they play (ITERATION_4 §1.1). */
    showIncludeAll: Boolean = true,
) {
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
            runSuspendCatching { calendar(change) }.onSuccess { subscription = it }.onFailure { failed = true }
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
        if (showIncludeAll) SwitchRow(stringResource(Res.string.calendar_include_all), current.includeAll, enabled = !working) { on -> run(CalendarSubscriptionChange(includeAll = on)) }
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
