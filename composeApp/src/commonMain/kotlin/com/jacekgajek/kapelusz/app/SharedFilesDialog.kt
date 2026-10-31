package com.jacekgajek.kapelusz.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.Attachments
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.Incoming
import com.jacekgajek.kapelusz.app.data.OpenEvent
import com.jacekgajek.kapelusz.app.data.SharedAttached
import com.jacekgajek.kapelusz.app.data.SharedFiles
import com.jacekgajek.kapelusz.app.data.SharedHandover
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.back
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.organiser
import com.jacekgajek.kapelusz.app.generated.share_cost
import com.jacekgajek.kapelusz.app.generated.share_event_files
import com.jacekgajek.kapelusz.app.generated.share_existing_collected
import com.jacekgajek.kapelusz.app.generated.share_existing_costs
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
import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.app.ui.components.IconCircle
import com.jacekgajek.kapelusz.app.ui.components.icon
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMonthShort
import com.jacekgajek.kapelusz.app.ui.screens.DraftThumbnail
import com.jacekgajek.kapelusz.app.ui.screens.ThumbnailBox
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.view.ShareEvent
import com.jacekgajek.kapelusz.view.ShareTarget
import com.jacekgajek.kapelusz.view.ShareTargets
import com.jacekgajek.kapelusz.view.partyName
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Photos or PDFs shared to Kapelusz (Android, ITERATION_6 §1), in a bottom sheet: "Attach to…" one of the events of
 * the user's bands, then what in it: a new cost or received money (its form opens with the files), the event's files
 * or an existing entry (attached at once, Undo on the event). A target without room for all the files is shown, but
 * dimmed, with why.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun SharedFilesDialog(incoming: Incoming, bands: BandRepository, fileStore: Attachments, state: BandState.Ready) {
    val shared = incoming.sharedFiles.collectAsState().value ?: return
    // Another share while open: starts again from the events.
    var chosen by remember(shared) { mutableStateOf<ShareEvent?>(null) }
    var busy by remember(shared) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (incoming.sharedFiles.value === shared) incoming.sharedFiles.value = null }
    // Not while attaching: closing would cancel it halfway (some files attached, the event not opened).
    // A sheet of its own per share: one shared while the last was closing would otherwise stay hidden.
    val sheet = key(shared) { rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy }) }
    // Every band on the device; recomputed as the open band changes (the others change only in the background).
    val events = remember(shared, state.band) { ShareTargets.events(bands.allBands(), state.userId, today()) }
    val severalBands = events.map { it.band.id }.distinct().size > 1
    val event = chosen
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = sheet) {
        // Back from an event to the events, like the arrow; from the events it closes the sheet.
        BackHandler(enabled = event != null && !busy) { chosen = null }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            SheetHeader(shared, event, severalBands, onBack = { chosen = null }.takeIf { event != null && !busy })
            Skipped(shared)
            when {
                shared.files.isEmpty() -> Text(stringResource(Res.string.share_nothing_usable), Modifier.padding(vertical = 16.dp))

                event == null -> EventChoices(events, severalBands) { chosen = it }

                else -> TargetChoices(event, state.userId, shared, enabled = !busy) { target ->
                    busy = true
                    scope.launch {
                        deliver(incoming, bands, fileStore, event, target, shared)
                        dismiss()
                    }
                }
            }
        }
    }
}

/** What is being attached (the files, on the right) and where: "Attach to…", or the chosen event with Back. */
@Composable
private fun SheetHeader(shared: SharedFiles, event: ShareEvent?, severalBands: Boolean, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (event != null) {
            IconButton(onClick = { onBack?.invoke() }, enabled = onBack != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
            }
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(event?.event?.name ?: stringResource(Res.string.share_title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (event != null) {
                Text(
                    listOfNotNull(formatDate(event.event.date, language), event.band.name.takeIf { severalBands }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FileThumbnails(shared)
    }
}

/** Up to three of the files as small squares (photos as themselves, PDFs as an icon), then how many more. */
@Composable
private fun FileThumbnails(shared: SharedFiles) {
    val shown = shared.files.take(MAX_THUMBNAILS)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { file ->
            if (file.kind == AttachmentKind.IMAGE) {
                DraftThumbnail(file, onRemove = null, size = THUMBNAIL)
            } else {
                ThumbnailBox(THUMBNAIL, onClick = null, name = file.fileName) {
                    Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (shared.files.size > shown.size) Text("+${shared.files.size - shown.size}", style = MaterialTheme.typography.labelLarge)
    }
}

private const val MAX_THUMBNAILS = 3
private val THUMBNAIL = 40.dp

/** What was left out, and why. */
@Composable
private fun Skipped(shared: SharedFiles) {
    val lines = listOf(
        PickResult.TooLarge to Res.plurals.share_skipped_too_large,
        PickResult.Unsupported to Res.plurals.share_skipped_unsupported,
        PickResult.Failed to Res.plurals.share_skipped_failed,
    ).mapNotNull { (result, text) -> shared.skipped.count { it == result }.takeIf { it > 0 }?.let { text to it } } +
        listOfNotNull(shared.tooMany.takeIf { it > 0 }?.let { Res.plurals.share_skipped_too_many to it })
    lines.forEach { (text, count) ->
        Text(pluralStringResource(text, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** A section's heading: smaller and in the primary colour, so it doesn't read as a choice. */
@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EventChoices(events: List<ShareEvent>, severalBands: Boolean, onChoose: (ShareEvent) -> Unit) {
    if (events.isEmpty()) Text(stringResource(Res.string.share_no_events), Modifier.padding(vertical = 16.dp))
    events.forEachIndexed { index, item ->
        if (item.upcoming && events.getOrNull(index - 1)?.upcoming != true) Heading(stringResource(Res.string.share_upcoming))
        val event = item.event
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChoose(item) }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DateBlock(event.date)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(event.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(item.band.name.takeIf { severalBands }, event.location).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The date as a calendar page: the day, the month under it, and the year when not this one. */
@Composable
private fun DateBlock(date: LocalDate) {
    Column(
        Modifier.width(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(date.day.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(formatMonthShort(date, language), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        // Past years' events would look like this year's.
        if (date.year != today().year) Text(date.year.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun TargetChoices(chosen: ShareEvent, userId: String, shared: SharedFiles, enabled: Boolean, onChoose: (ShareTarget) -> Unit) {
    val (view, event) = chosen
    val band = view.band
    val targets = ShareTargets.targets(view, userId, event)
    val needed = shared.files.size

    @Composable
    fun room(target: ShareTarget): String? = when {
        target.room >= needed -> null
        target.room <= 0 -> stringResource(Res.string.share_full)
        else -> pluralStringResource(Res.plurals.share_room, target.room, target.room)
    }
    // New entries as big tiles, side by side: the usual choice, and clearly buttons.
    val tiles = targets.filter { it is ShareTarget.NewCost || it is ShareTarget.NewCollected || it is ShareTarget.EventFiles }
    if (tiles.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { target ->
                val (icon, label) = when (target) {
                    is ShareTarget.NewCost -> Icons.AutoMirrored.Filled.ReceiptLong to Res.string.share_new_cost
                    is ShareTarget.NewCollected -> Icons.Default.Payments to Res.string.share_new_collected
                    else -> Icons.Default.AttachFile to Res.string.share_event_files
                }
                NewTile(icon, stringResource(label), room(target), enabled && target.room >= needed, Modifier.weight(1f).fillMaxHeight()) { onChoose(target) }
            }
        }
    }
    val fund = stringResource(Res.string.band_fund)
    val organiser = stringResource(Res.string.organiser)
    val costLabel = stringResource(Res.string.share_cost)
    val costs = targets.filterIsInstance<ShareTarget.ExistingCost>()
    // Existing costs and received money under their own headings: "Bob · 100 zł" could be either.
    if (costs.isNotEmpty()) Heading(stringResource(Res.string.share_existing_costs))
    costs.forEach { target ->
        val cost = target.cost
        ExistingRow(
            icon = cost.category.icon(),
            title = cost.description ?: cost.category?.label() ?: costLabel,
            subtitle = band.member(cost.memberId)?.name,
            amount = formatMoney(cost.amount, band.currency, language),
            problem = room(target),
            enabled = enabled && target.room >= needed,
        ) { onChoose(target) }
    }
    val collected = targets.filterIsInstance<ShareTarget.ExistingCollected>()
    if (collected.isNotEmpty()) Heading(stringResource(Res.string.share_existing_collected))
    collected.forEach { target ->
        val holding = target.holding
        ExistingRow(
            icon = Icons.Default.Payments,
            title = if (holding.awaited) organiser else partyName(band, event, holding.partyId, fund),
            subtitle = listOfNotNull(holding.source.label(), holding.note).joinToString(" · "),
            amount = formatMoney(holding.amount, band.currency, language),
            problem = room(target),
            enabled = enabled && target.room >= needed,
        ) { onChoose(target) }
    }
}

/** A new entry for the files: an icon over its name on a tonal tile; without room, dimmed with why under it. */
@Composable
private fun NewTile(icon: ImageVector, label: String, problem: String?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Dimmed when it has no room, but not why (below): that is the point of showing it.
            val dim = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
            Icon(icon, null, dim.size(28.dp))
            Text(label, dim, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            if (problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** An existing cost or received money, like on the event's Money page; the clip at the end says the files go there. */
@Composable
private fun ExistingRow(icon: ImageVector, title: String, subtitle: String?, amount: String, problem: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dimmed when it has no room, but not why (under the name): that is the point of showing it.
        val dim = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
        IconCircle(icon, dim)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, dim, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = problem ?: subtitle?.takeIf { it.isNotEmpty() }
            if (second != null) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(amount, dim, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.Default.AttachFile, null, dim.padding(start = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

private const val DISABLED_ALPHA = 0.45f

/**
 * Takes the shared files where the user chose: opens the event's band if another one is open, then either hands the
 * files to the new cost or received money form, or attaches them and remembers them for the event's Undo; the event
 * then opens (at the section where they went).
 */
private suspend fun deliver(incoming: Incoming, bands: BandRepository, fileStore: Attachments, chosen: ShareEvent, target: ShareTarget, shared: SharedFiles) {
    val bandId = chosen.band.id
    val eventId = chosen.event.id
    if (bands.currentBandId != bandId) {
        // Like an event link: showing the other band must not reset the screens opened below.
        incoming.navigatedForBand = bandId
        bands.open(bandId)
    }
    val owner = when (target) {
        is ShareTarget.NewCost, is ShareTarget.NewCollected -> null
        is ShareTarget.EventFiles -> AttachmentOwner.OfEvent(eventId)
        is ShareTarget.ExistingCost -> AttachmentOwner.OfCost(target.cost.id)
        is ShareTarget.ExistingCollected -> AttachmentOwner.OfHolding(eventId, target.holding.id)
    }
    if (owner == null) {
        incoming.sharedHandover.value = SharedHandover(eventId, toCost = target is ShareTarget.NewCost, shared.files)
    } else {
        val attached = shared.files.mapNotNull { file -> newId().takeIf { id -> fileStore.attach(owner, file, id) == null } }
        incoming.sharedAttached.value = SharedAttached(bandId, eventId, owner, attached, failed = shared.files.size - attached.size)
    }
    val section = when (target) {
        is ShareTarget.NewCost -> null
        is ShareTarget.NewCollected, is ShareTarget.ExistingCollected -> EventSection.COLLECTED
        is ShareTarget.EventFiles -> EventSection.FILES
        is ShareTarget.ExistingCost -> EventSection.REFUNDS
    }
    incoming.pendingOpenEvent.value = OpenEvent(bandId, eventId, section)
}
