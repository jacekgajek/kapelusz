package com.jacekgajek.kapelusz.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.SharedAttached
import com.jacekgajek.kapelusz.app.data.SharedFiles
import com.jacekgajek.kapelusz.app.data.SharedHandover
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.back
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.organiser
import com.jacekgajek.kapelusz.app.generated.share_cost
import com.jacekgajek.kapelusz.app.generated.share_event_files
import com.jacekgajek.kapelusz.app.generated.share_existing
import com.jacekgajek.kapelusz.app.generated.share_full
import com.jacekgajek.kapelusz.app.generated.share_new_collected
import com.jacekgajek.kapelusz.app.generated.share_new_cost
import com.jacekgajek.kapelusz.app.generated.share_no_events
import com.jacekgajek.kapelusz.app.generated.share_nothing_usable
import com.jacekgajek.kapelusz.app.generated.share_room
import com.jacekgajek.kapelusz.app.generated.share_skipped_failed
import com.jacekgajek.kapelusz.app.generated.share_skipped_too_large
import com.jacekgajek.kapelusz.app.generated.share_skipped_too_many
import com.jacekgajek.kapelusz.app.generated.share_skipped_unsupported
import com.jacekgajek.kapelusz.app.generated.share_title
import com.jacekgajek.kapelusz.app.generated.share_upcoming
import com.jacekgajek.kapelusz.app.platform.PickResult
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.ShareEvent
import com.jacekgajek.kapelusz.app.ui.model.ShareTarget
import com.jacekgajek.kapelusz.app.ui.model.ShareTargets
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.app.ui.screens.DraftThumbnail
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Photos or PDFs shared to Kapelusz (Android, ITERATION_6 §1): "Attach to…" one of the events of the user's bands,
 * then what in it: a new cost or received money (its form opens with the files), the event's files or an existing
 * entry (attached at once, Undo on the event). A target without room for all the files is shown, but disabled.
 */
@Composable
internal fun SharedFilesDialog(graph: AppGraph, state: BandState.Ready) {
    val shared = graph.sharedFiles.collectAsState().value ?: return
    // Another share while open: starts again from the events.
    var chosen by remember(shared) { mutableStateOf<ShareEvent?>(null) }
    var busy by remember(shared) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (graph.sharedFiles.value === shared) graph.sharedFiles.value = null }
    // Not while attaching: closing would cancel it halfway (some files attached, the event not opened).
    val dismissUnlessBusy = { if (!busy) dismiss() }
    // Every band on the device; recomputed as the open band changes (the others change only in the background).
    val events = remember(shared, state.band) { ShareTargets.events(graph.bands.allBands(), state.userId, today()) }
    val severalBands = events.map { it.band.id }.distinct().size > 1
    val event = chosen
    AlertDialog(
        onDismissRequest = dismissUnlessBusy,
        title = { Text(event?.event?.name ?: stringResource(Res.string.share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SharedFilesPreview(shared)
                when {
                    shared.files.isEmpty() -> Text(stringResource(Res.string.share_nothing_usable))
                    event == null -> EventChoices(events, severalBands) { chosen = it }
                    else -> TargetChoices(event, state.userId, shared, enabled = !busy) { target ->
                        busy = true
                        scope.launch {
                            deliver(graph, event, target, shared)
                            dismiss()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (event != null) TextButton(enabled = !busy, onClick = { chosen = null }) { Text(stringResource(Res.string.back)) }
            else TextButton(onClick = dismiss) { Text(stringResource(if (shared.files.isEmpty()) Res.string.close else Res.string.cancel)) }
        },
    )
}

/** The shared files: photos as small thumbnails, PDFs by name; then what was left out, and why. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedFilesPreview(shared: SharedFiles) {
    val images = shared.files.filter { it.kind == AttachmentKind.IMAGE }
    if (images.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            images.forEach { DraftThumbnail(it, onRemove = null, size = 48.dp) }
        }
    }
    shared.files.filter { it.kind == AttachmentKind.PDF }.forEach { pdf ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pdf.fileName, Modifier.padding(start = 8.dp), maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
    }
    listOf(
        PickResult.TooLarge to Res.plurals.share_skipped_too_large,
        PickResult.Unsupported to Res.plurals.share_skipped_unsupported,
        PickResult.Failed to Res.plurals.share_skipped_failed,
    ).forEach { (result, text) ->
        val count = shared.skipped.count { it == result }
        if (count > 0) Text(pluralStringResource(text, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (shared.tooMany > 0) {
        Text(pluralStringResource(Res.plurals.share_skipped_too_many, shared.tooMany, shared.tooMany), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun EventChoices(events: List<ShareEvent>, severalBands: Boolean, onChoose: (ShareEvent) -> Unit) {
    if (events.isEmpty()) Text(stringResource(Res.string.share_no_events))
    events.forEachIndexed { index, item ->
        if (item.upcoming && events.getOrNull(index - 1)?.upcoming != true) {
            Text(stringResource(Res.string.share_upcoming), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        val date = formatDate(item.event.date, language)
        Choice(item.event.name, if (severalBands) "$date · ${item.band.name}" else date) { onChoose(item) }
    }
}

@Composable
private fun TargetChoices(chosen: ShareEvent, userId: String, shared: SharedFiles, enabled: Boolean, onChoose: (ShareTarget) -> Unit) {
    val (band, event) = chosen
    val targets = ShareTargets.targets(band, userId, event)
    val needed = shared.files.size
    val fund = stringResource(Res.string.band_fund)
    val organiser = stringResource(Res.string.organiser)
    val costLabel = stringResource(Res.string.share_cost)
    targets.forEachIndexed { index, target ->
        val existing = target is ShareTarget.ExistingCost || target is ShareTarget.ExistingCollected
        val previousExisting = targets.getOrNull(index - 1).let { it is ShareTarget.ExistingCost || it is ShareTarget.ExistingCollected }
        if (existing && !previousExisting) Text(stringResource(Res.string.share_existing), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
        val title = when (target) {
            is ShareTarget.NewCost -> stringResource(Res.string.share_new_cost)
            is ShareTarget.NewCollected -> stringResource(Res.string.share_new_collected)
            is ShareTarget.EventFiles -> stringResource(Res.string.share_event_files)
            is ShareTarget.ExistingCost -> listOfNotNull(
                target.cost.description ?: target.cost.category?.label() ?: costLabel,
                formatMoney(target.cost.amount, band.currency, language),
                band.member(target.cost.memberId)?.name,
            ).joinToString(" · ")
            is ShareTarget.ExistingCollected -> listOf(
                if (target.holding.awaited) organiser else partyName(band, event, target.holding.partyId, fund),
                formatMoney(target.holding.amount, band.currency, language),
            ).joinToString(" · ")
        }
        val fits = target.room >= needed
        val note = when {
            fits -> null
            target.room <= 0 -> stringResource(Res.string.share_full)
            else -> pluralStringResource(Res.plurals.share_room, target.room, target.room)
        }
        Choice(title, note, enabled = enabled && fits, noteIsProblem = !fits) { onChoose(target) }
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, enabled: Boolean = true, noteIsProblem: Boolean = false, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.5f
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        if (subtitle != null) Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (noteIsProblem) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

/**
 * Takes the shared files where the user chose: opens the event's band if another one is open, then either hands the
 * files to the new cost or received money form, or attaches them and remembers them for the event's Undo; the event
 * then opens (at the section where they went).
 */
private suspend fun deliver(graph: AppGraph, chosen: ShareEvent, target: ShareTarget, shared: SharedFiles) {
    val bandId = chosen.band.id
    val eventId = chosen.event.id
    if (graph.bands.currentBandId != bandId) {
        // Like a tapped notification: showing the other band must not reset the screens opened below.
        graph.navigatedForBand = bandId
        graph.bands.open(bandId)
    }
    val owner = when (target) {
        is ShareTarget.NewCost, is ShareTarget.NewCollected -> null
        is ShareTarget.EventFiles -> AttachmentOwner.OfEvent(eventId)
        is ShareTarget.ExistingCost -> AttachmentOwner.OfCost(target.cost.id)
        is ShareTarget.ExistingCollected -> AttachmentOwner.OfHolding(eventId, target.holding.id)
    }
    if (owner == null) {
        graph.sharedHandover.value = SharedHandover(eventId, toCost = target is ShareTarget.NewCost, shared.files)
    } else {
        val attached = shared.files.mapNotNull { file -> newId().takeIf { id -> graph.attachments.attach(owner, file, id) == null } }
        graph.sharedAttached.value = SharedAttached(bandId, eventId, owner, attached, failed = shared.files.size - attached.size)
    }
    val section = when (target) {
        is ShareTarget.NewCost -> null
        is ShareTarget.NewCollected, is ShareTarget.ExistingCollected -> "COLLECTED"
        is ShareTarget.EventFiles -> "FILES"
        is ShareTarget.ExistingCost -> "REFUNDS"
    }
    graph.pendingEventSection.value = section?.let { eventId to it }
    graph.pendingOpenEvent.value = bandId to eventId
}
