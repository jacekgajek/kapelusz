package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.jacekgajek.kapelusz.app.generated.roster_archived
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.search_by_title
import com.jacekgajek.kapelusz.app.generated.search_people_none
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.GuestIcon
import com.jacekgajek.kapelusz.app.ui.components.NameSearchField
import com.jacekgajek.kapelusz.app.ui.components.NotInAppIcon
import com.jacekgajek.kapelusz.app.ui.components.RoleGroup
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.shareText
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.rules.Lineup
import com.jacekgajek.kapelusz.text.Names
import com.jacekgajek.kapelusz.text.sortedByName
import org.jetbrains.compose.resources.stringResource

/** One person in the picker: a member, one of this event's guests, or a guest From an event… brought. */
private class PickRow(val id: Id, val name: String, val pictureUrl: String?, val guest: Boolean, val notInApp: Boolean, val share: Participant?)

/**
 * The line-up picker (ITERATION_8 §1.2): everyone who can take part by role, this event's guests, ticks for who takes
 * part; From an event…, search, tick all; Save sends one [com.jacekgajek.kapelusz.ops.ChangeLineup]. Read only for
 * [readOnly] (who doesn't manage participants): who isn't taking part, without ticks.
 */
@Composable
fun LineupPicker(context: EventContext, readOnly: Boolean, onDismiss: () -> Unit) {
    val band = context.band
    val event = context.event
    // Changes on top of the event as it is now: one synced meanwhile stays (ITERATION_8 §1.2).
    var draft by remember(event.id) { mutableStateOf(Lineup.Draft()) }
    val ticked = draft.ticked(event)
    var query by remember { mutableStateOf("") }
    var choosingSource by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val change = if (readOnly) null else Lineup.change(band, event, draft)
    val close = { if (change != null) discarding = true else onDismiss() }

    // Who can be added, and who takes part though they no longer could be added (a former member, now a viewer):
    // shown, so nothing changes unseen. Read only: who isn't taking part.
    val members = (Lineup.candidates(band) + event.participants.mapNotNull { band.member(it.partyId) }).distinctBy { it.id }
        .filter { !readOnly || event.participant(it.id) == null }.sortedByName { it.name }
    val guests = if (readOnly) emptyList() else (event.guests.filter { event.participant(it.id) != null } + draft.newGuests).sortedByName { it.name }
    val shareOf = { id: Id -> event.participant(id) ?: draft.shares[id] ?: band.member(id)?.asParticipant() }
    val shown = { name: String -> query.isBlank() || Names.matches(name, query) }
    val row = { m: Member -> PickRow(m.id, m.name, m.pictureUrl, guest = false, notInApp = m.userId == null, share = shareOf(m.id)) }
    val (former, current) = members.filter { shown(it.name) }.partition { it.archived }
    val groups = RoleGroup.entries.map { group -> stringResource(group.title, current.count(group.has)) to current.filter(group.has).map(row) } +
        listOf(stringResource(Res.string.roster_archived, former.size) to former.map(row)) +
        listOf(stringResource(Res.string.participants_guests) to guests.filter { shown(it.name) }.map { PickRow(it.id, it.name, null, guest = true, notInApp = false, share = shareOf(it.id)) })
    val visible = groups.flatMap { it.second }
    val locked = { id: Id -> event.participant(id) != null && Lineup.locked(band, event, id) }
    val toggleable = visible.filter { !locked(it.id) }
    val allTicked = toggleable.all { it.id in ticked }
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
                                val ids = toggleable.map { it.id }.toSet()
                                draft = draft.ticking(event, if (allTicked) ticked - ids else ticked + ids)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(if (allTicked) Res.string.lineup_untick_all else Res.string.lineup_tick_all)) }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        groups.forEachIndexed { index, (title, rows) ->
                            if (rows.isEmpty()) return@forEachIndexed
                            item(key = "group-$index") { PickHeading(title) }
                            items(rows, key = { it.id }) { row ->
                                PickRowItem(context, row, row.id in ticked, readOnly, locked(row.id)) { draft = draft.toggled(event, row.id) }
                            }
                        }
                        if (visible.isEmpty()) item(key = "none") { Text(stringResource(Res.string.search_people_none, query.trim()), Modifier.padding(16.dp)) }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (readOnly) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
                        } else {
                            Text(stringResource(Res.string.lineup_count, ticked.size, total), Modifier.weight(1f))
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
            confirmButton = {
                TextButton(onClick = {
                    discarding = false
                    onDismiss()
                }) { Text(stringResource(Res.string.lineup_discard)) }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(Res.string.lineup_keep_editing)) } },
        )
    }
}

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
        // Read only: no ticks (ITERATION_8 §1.2), the list is who isn't taking part.
        if (readOnly) Spacer(Modifier.width(12.dp)) else Checkbox(ticked, { onToggle() }, enabled = enabled)
        if (row.guest) {
            GuestIcon()
        } else if (row.notInApp) {
            NotInAppIcon()
        } else {
            Avatar(row.name, row.pictureUrl, decorative = true)
        }
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

/** From an event…: another event, newest first, searchable by name. */
@Composable
internal fun StartFromDialog(context: EventContext, onDismiss: () -> Unit, onPick: (Event) -> Unit) {
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
                    if (events.size > 5) NameSearchField(query, { query = it }, placeholder = Res.string.search_by_title)
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
