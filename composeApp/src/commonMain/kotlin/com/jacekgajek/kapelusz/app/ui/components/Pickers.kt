package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.domain.Names
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.jacekgajek.kapelusz.app.generated.range_from_to
import com.jacekgajek.kapelusz.app.generated.range_pick_end
import com.jacekgajek.kapelusz.app.generated.range_pick_start
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/** Read-only field showing a date; the calendar button opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate, onDate: (LocalDate) -> Unit, label: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = formatDate(date, language),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Default.CalendarMonth, contentDescription = label) } },
        modifier = modifier.fillMaxWidth(),
    )
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date) }
                    open = false
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** A date range in a dialog (e.g. a summary's period, ITERATION_6 §7): OK once both ends are picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(from: LocalDate?, to: LocalDate?, onRange: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    fun LocalDate.millis() = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    fun Long.date() = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
    val state = rememberDateRangePickerState(initialSelectedStartDateMillis = from?.millis(), initialSelectedEndDateMillis = to?.millis())
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = start != null && end != null, onClick = {
                if (start != null && end != null) onRange(start.date(), end.date())
                onDismiss()
            }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    ) {
        // No "Select dates" title (the calendar is obvious), and the range in the app's short dates on one line: the
        // picker's own headline wrapped in Polish ("27 paź 2025 - 26 paź / 2026").
        DateRangePicker(
            state,
            Modifier.weight(1f),
            title = null,
            headline = {
                val lang = language
                val first = start?.let { formatDate(it.date(), lang) }
                val last = end?.let { formatDate(it.date(), lang) }
                val text = when {
                    first == null -> stringResource(Res.string.range_pick_start)
                    last == null -> "$first – …"
                    else -> "$first – $last"
                }
                val spoken = when {
                    first == null -> text
                    last == null -> stringResource(Res.string.range_pick_end, first)
                    else -> stringResource(Res.string.range_from_to, first, last)
                }
                // Smaller rather than cut off (a long language, a large font); read out on each tap, as Material's own.
                Text(
                    text,
                    Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = spoken
                    },
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp),
                )
            },
        )
    }
}

/**
 * Dropdown choosing one of [options]; [label] renders each option. With more than [Crowd.THRESHOLD] options its menu
 * starts with a search field (ITERATION_8 §1.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    optionLabel: @Composable (T) -> String,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        var query by remember { mutableStateOf("") }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; query = "" }) {
            val searchable = options.size > Crowd.THRESHOLD
            if (searchable) NameSearchField(query, { query = it }, Modifier.padding(horizontal = 8.dp))
            options.forEach { option ->
                val text = optionLabel(option)
                if (!searchable || Names.matches(text, query)) {
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(option); expanded = false; query = "" })
                }
            }
        }
    }
}

/** A 24-hour time picker in a dialog; [initial] preselects it (default 20:00, a typical concert start). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: LocalTime?, title: String, onTime: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial?.hour ?: 20, initialMinute = initial?.minute ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onTime(LocalTime(state.hour, state.minute)); onDismiss() }) { Text(stringResource(Res.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
