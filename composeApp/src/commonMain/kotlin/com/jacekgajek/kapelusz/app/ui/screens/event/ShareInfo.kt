package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.info_date
import com.jacekgajek.kapelusz.app.generated.info_lineup
import com.jacekgajek.kapelusz.app.generated.info_map
import com.jacekgajek.kapelusz.app.generated.info_place
import com.jacekgajek.kapelusz.app.generated.info_plan
import com.jacekgajek.kapelusz.app.generated.share_info
import com.jacekgajek.kapelusz.app.generated.share_info_copied
import com.jacekgajek.kapelusz.app.generated.share_info_failed
import com.jacekgajek.kapelusz.app.generated.share_text_footer
import com.jacekgajek.kapelusz.app.platform.ShareResult
import com.jacekgajek.kapelusz.app.platform.rememberShare
import com.jacekgajek.kapelusz.app.ui.model.displayLabel
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.app.ui.model.setTimes
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The labels of the shared gig info, in the app's language. */
class InfoWords(val date: String, val place: String, val map: String, val plan: String, val lineup: String, val footer: String)

/**
 * The gig to send to someone (ITERATION_5 §3): its name; date, place and the map pin; the plan, a time per line; the
 * notes as they are; the line-up on one line; the app's address last, as under a shared settlement. Parts apart by a
 * blank line; what the event does not have is left out.
 */
fun eventInfoText(event: Event, plan: List<String>, lineup: List<String>, words: InfoWords): String {
    val date = event.date.let { "${it.day.toString().padStart(2, '0')}.${it.month.ordinal.plus(1).toString().padStart(2, '0')}.${it.year}" }
    val where = listOfNotNull(
        "${words.date}: $date",
        event.location?.takeIf { it.isNotBlank() }?.let { "${words.place}: ${it.trim()}" },
        // Only an exact pin: a search link made from a place name would be long and no better than the name.
        event.mapUrl?.let { "${words.map}: $it" },
    )
    return listOfNotNull(
        event.name,
        where.joinToString("\n"),
        plan.takeIf { it.isNotEmpty() }?.let { (listOf(words.plan) + it.map { line -> "- $line" }).joinToString("\n") },
        event.notes?.trim()?.takeIf { it.isNotEmpty() },
        lineup.takeIf { it.isNotEmpty() }?.let { "${words.lineup}: ${it.joinToString(", ")}" },
        words.footer,
    ).joinToString("\n\n")
}

/** Shares the gig info as text: the share sheet, or the clipboard where there is none (said in the snackbar). */
@Composable
internal fun rememberShareInfo(band: Band, event: Event, snackbar: SnackbarHostState): () -> Unit {
    val share = rememberShare()
    val scope = rememberCoroutineScope()
    val words = InfoWords(
        stringResource(Res.string.info_date),
        stringResource(Res.string.info_place),
        stringResource(Res.string.info_map),
        stringResource(Res.string.info_plan),
        stringResource(Res.string.info_lineup),
        stringResource(Res.string.share_text_footer),
    )
    val plan = event.setTimes().map { "${it.displayLabel()} ${formatAgendaTime(it)}" }
    val lineup = event.participants.map { partyName(band, event, it.partyId, "") }
    val copied = stringResource(Res.string.share_info_copied)
    val failed = stringResource(Res.string.share_info_failed)
    return {
        val text = eventInfoText(event, plan, lineup, words)
        scope.launch {
            when (share(text)) {
                ShareResult.COPIED -> snackbar.showSnackbar(copied)
                ShareResult.FAILED -> snackbar.showSnackbar(failed)
                ShareResult.SHARED -> {}
            }
        }
    }
}

/** "Share info" at the end of the gig page, for everyone, drafts too. */
@Composable
internal fun ShareInfoButton(onShare: () -> Unit) {
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.Default.Share, null)
        Text(stringResource(Res.string.share_info), Modifier.padding(start = 8.dp))
    }
}
