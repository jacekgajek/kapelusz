package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.lineup_changed
import com.jacekgajek.kapelusz.app.generated.lineup_count
import com.jacekgajek.kapelusz.app.generated.lineup_discard
import com.jacekgajek.kapelusz.app.generated.lineup_discard_title
import com.jacekgajek.kapelusz.app.generated.lineup_keep_editing
import com.jacekgajek.kapelusz.app.generated.lineup_locked
import com.jacekgajek.kapelusz.app.generated.lineup_start_from
import com.jacekgajek.kapelusz.app.generated.lineup_start_from_none
import com.jacekgajek.kapelusz.app.generated.lineup_start_from_title
import com.jacekgajek.kapelusz.app.generated.lineup_tick_all
import com.jacekgajek.kapelusz.app.generated.lineup_title
import com.jacekgajek.kapelusz.app.generated.lineup_untick_all
import com.jacekgajek.kapelusz.app.generated.participants_guests
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.search_people_none
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.GuestIcon
import com.jacekgajek.kapelusz.app.ui.components.NameSearchField
import com.jacekgajek.kapelusz.app.ui.components.NotInAppIcon
import com.jacekgajek.kapelusz.app.ui.components.RoleGroup
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.shareText
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.domain.Lineup
import com.jacekgajek.kapelusz.domain.Names
import com.jacekgajek.kapelusz.domain.sortedByName
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Participant
import org.jetbrains.compose.resources.stringResource

/** One person in the picker: a member, one of this event's guests, or a guest Start from… brought. */
private class PickRow(val id: Id, val name: String, val pictureUrl: String?, val guest: Boolean, val notInApp: Boolean, val share: Participant?)

/**
 * The line-up picker (ITERATION_8 §1.2): everyone who can take part by role, this event's guests, ticks for who takes
 * part; Start from…, search, tick all; Save sends one [com.jacekgajek.kapelusz.ops.ChangeLineup]. Read only for
 * [readOnly] (who doesn't manage participants): the same list, nothing to change.
 */
@Composable
fun LineupPicker(context: EventContext, readOnly: Boolean, onDismiss: () -> Unit) {
    val band = context.band
    val event = context.event
    var draft by remember(event.id) { mutableStateOf(Lineup.Draft.of(event)) }
    var query by remember { mutableStateOf("") }
    var choosingSource by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val change = if (readOnly) null else Lineup.change(band, event, draft)
    val close = { if (change != null) discarding = true else onDismiss() }

    val members = Lineup.candidates(band)
    val guests = (event.guests.filter { event.participant(it.id) != null } + draft.newGuests).sortedByName { it.name }
    val shareOf = { id: Id -> event.participant(id) ?: draft.shares[id] ?: band.member(id)?.asParticipant() }
    val shown = { name: String -> query.isBlank() || Names.matches(name, query) }
    val groups = RoleGroup.entries.map { group ->
        group to members.filter { group.has(it) && shown(it.name) }
            .map { PickRow(it.id, it.name, it.pictureUrl, guest = false, notInApp = it.userId == null, share = shareOf(it.id)) }
    }.filter { it.second.isNotEmpty() }
    val guestRows = guests.filter { shown(it.name) }.map { PickRow(it.id, it.name, null, guest = true, notInApp = false, share = shareOf(it.id)) }
    val visible = groups.flatMap { it.second } + guestRows
    val locked = { id: Id -> event.participant(id) != null && Lineup.locked(band, event, id) }
    val toggleable = visible.filter { !locked(it.id) }
    val allTicked = toggleable.all { it.id in draft.ticked }
    val total = members.size + guests.size

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Full screen on a phone; a dialog about 560 dp wide on a tablet or the web.
            val wide = maxWidth >= 600.dp
            Surface(
                if (wide) Modifier.width(560.dp).fillMaxHeight(0.9f) else Modifier.fillMaxSize(),
                shape = if (wide) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.extraSmall,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = close) { Icon(Icons.Default.Close, stringResource(Res.string.close)) }
                        Text(stringResource(Res.string.lineup_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (!readOnly) TextButton(onClick = { choosingSource = true }) { Text(stringResource(Res.string.lineup_start_from)) }
                    }
                    NameSearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp))
                    if (!readOnly && toggleable.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val ids = toggleable.map { it.id }
                                draft = draft.copy(ticked = if (allTicked) draft.ticked - ids.toSet() else draft.ticked + ids)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(if (allTicked) Res.string.lineup_untick_all else Res.string.lineup_tick_all)) }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        for ((group, rows) in groups) {
                            item(key = "group-${group.name}") { PickHeading(stringResource(group.title, rows.size)) }
                            items(rows, key = { it.id }) { row -> PickRowItem(context, row, row.id in draft.ticked, readOnly, locked(row.id)) { draft = draft.toggled(row.id) } }
                        }
                        if (guestRows.isNotEmpty()) {
                            item(key = "guests") { PickHeading(stringResource(Res.string.participants_guests)) }
                            items(guestRows, key = { it.id }) { row -> PickRowItem(context, row, row.id in draft.ticked, readOnly, locked(row.id)) { draft = draft.toggled(row.id) } }
                        }
                        if (visible.isEmpty()) item(key = "none") { Text(stringResource(Res.string.search_people_none, query.trim()), Modifier.padding(16.dp)) }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.lineup_count, draft.ticked.size, total), Modifier.weight(1f))
                        if (readOnly) {
                            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
                        } else {
                            val message = change?.let { stringResource(Res.string.lineup_changed, it.add.size, it.remove.size) }
                            Button(enabled = change != null, onClick = {
                                if (change != null && context.actions.runWithUndo(change, message!!)) onDismiss()
                            }) { Text(stringResource(Res.string.save)) }
                        }
                    }
                }
            }
        }
    }

    if (choosingSource) {
        StartFromDialog(context, onDismiss = { choosingSource = false }) { source ->
            draft = Lineup.startFrom(band, event, source) { newId() }
            choosingSource = false
        }
    }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text(stringResource(Res.string.lineup_discard_title)) },
            confirmButton = { TextButton(onClick = { discarding = false; onDismiss() }) { Text(stringResource(Res.string.lineup_discard)) } },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(Res.string.lineup_keep_editing)) } },
        )
    }
}

private fun Lineup.Draft.toggled(id: Id) = copy(ticked = if (id in ticked) ticked - id else ticked + id)

/** A group's heading: quieter than the members screen's, the list is about the ticks. */
@Composable
private fun PickHeading(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PickRowItem(context: EventContext, row: PickRow, ticked: Boolean, readOnly: Boolean, locked: Boolean, onToggle: () -> Unit) {
    val enabled = !readOnly && !locked
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(ticked, { onToggle() }, enabled = enabled)
        if (row.guest) GuestIcon() else if (row.notInApp) NotInAppIcon() else Avatar(row.name, row.pictureUrl, decorative = true)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(row.name)
            // The share only when not the default (ITERATION_8 §1.2); changed on the participant's row in the event.
            row.share?.let { shareText(it.weight, it.fixedShare, it.percent, context.currency) }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (locked) Text(stringResource(Res.string.lineup_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Start from…: another event, newest first, searchable by name. */
@Composable
private fun StartFromDialog(context: EventContext, onDismiss: () -> Unit, onPick: (Event) -> Unit) {
    var query by remember { mutableStateOf("") }
    val lang = language
    val events = context.band.events.filter { it.id != context.event.id && it.participants.isNotEmpty() }
        .sortedWith(compareByDescending<Event> { it.date }.thenByDescending { it.updatedAt })
    val shown = events.filter { query.isBlank() || Names.matches(it.name, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lineup_start_from_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (events.isEmpty()) {
                    Text(stringResource(Res.string.lineup_start_from_none))
                } else {
                    if (events.size > 5) NameSearchField(query, { query = it })
                    Box(Modifier.heightIn(max = 400.dp)) {
                        LazyColumn {
                            items(shown, key = { it.id }) { event ->
                                Column(Modifier.fillMaxWidth().clickable { onPick(event) }.padding(vertical = 10.dp)) {
                                    Text(event.name)
                                    Text(
                                        "${formatDate(event.date, lang)} · ${event.participants.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
