package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.details
import com.jacekgajek.kapelusz.app.generated.details_add
import com.jacekgajek.kapelusz.app.generated.details_discard
import com.jacekgajek.kapelusz.app.generated.details_discard_title
import com.jacekgajek.kapelusz.app.generated.details_hint
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.show_all
import com.jacekgajek.kapelusz.app.generated.show_less
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.UpdateEvent
import org.jetbrains.compose.resources.stringResource

private const val COLLAPSED_LINES = 6

/**
 * Free-text details of an event (ITERATION_2 §4.2), e.g. a pasted setlist: line breaks kept, long text collapsed,
 * selectable for copying. Stored as the event's notes. Leads and above edit it in a full-screen editor.
 */
@Composable
fun DetailsSection(context: EventContext) {
    val event = context.event
    val canEdit = context.can.editEvent(event)
    val text = event.notes
    var editing by remember { mutableStateOf(false) }
    var expanded by remember(event.id) { mutableStateOf(false) }
    if (text == null && !canEdit) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(Res.string.details), Modifier.weight(1f))
        if (canEdit && text != null) EditButton { editing = true }
    }
    if (text == null) {
        TextButton(onClick = { editing = true }) { Text(stringResource(Res.string.details_add)) }
    } else {
        var overflowing by remember(text) { mutableStateOf(false) }
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
            )
        }
        if (overflowing || expanded) {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) Res.string.show_less else Res.string.show_all)) }
        }
    }
    if (editing) {
        DetailsEditor(text.orEmpty(), onSave = { value ->
            // Trimmed like the server does, so an unchanged text sends nothing.
            val cleaned = value.trim().ifBlank { null }
            cleaned == text || context.actions.run(UpdateEvent(event.id, notes = Change(cleaned)))
        }, onDismiss = { editing = false })
    }
}

/** Full screen, so a long setlist is comfortable to paste and edit. */
@Composable
private fun DetailsEditor(initial: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // A pasted setlist is easy to lose: leaving with changes asks first.
    val leave = { if (value != initial) confirmDiscard = true else onDismiss() }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                BackTopBar(stringResource(Res.string.details), leave) {
                    // Refused (the snackbar says why): the editor stays, with what was typed (V5-M1 C4a).
                    TextButton(enabled = value.trim().length <= Limits.MAX_NOTES, onClick = { if (onSave(value)) onDismiss() }) {
                        Text(stringResource(Res.string.save))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value,
                    { value = it },
                    placeholder = { Text(stringResource(Res.string.details_hint)) },
                    // How much room is left, so a long setlist isn't refused only on Save.
                    supportingText = { Text("${value.trim().length} / ${Limits.MAX_NOTES}") },
                    isError = value.trim().length > Limits.MAX_NOTES,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.details_discard_title),
            text = null,
            confirmLabel = stringResource(Res.string.details_discard),
            onConfirm = onDismiss,
            onDismiss = { confirmDiscard = false },
        )
    }
}
