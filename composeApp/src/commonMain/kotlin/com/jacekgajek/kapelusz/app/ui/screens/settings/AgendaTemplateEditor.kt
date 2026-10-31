package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.agenda_add_time
import com.jacekgajek.kapelusz.app.generated.agenda_label
import com.jacekgajek.kapelusz.app.generated.agenda_template_hint
import com.jacekgajek.kapelusz.app.generated.agenda_template_reset
import com.jacekgajek.kapelusz.app.generated.language
import com.jacekgajek.kapelusz.app.generated.move_up
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.text.TemplateDraft
import com.jacekgajek.kapelusz.app.ui.text.label
import com.jacekgajek.kapelusz.app.ui.text.toItem
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import org.jetbrains.compose.resources.stringResource

/**
 * The agenda times new events get (leaders, ITERATION_2 §4.1): rename, reorder, add, remove. Built-in entries keep
 * following the user's language until renamed. Changes apply to events created from now on.
 */
@Composable
internal fun AgendaTemplateEditor(template: List<AgendaTemplateItem>, storedOwnList: Boolean, onSave: (List<AgendaTemplateItem>) -> Unit) {
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
                // A label, so screen readers say what the field is for (ITERATION_3 R13).
                label = { Text(stringResource(Res.string.agenda_label)) },
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
