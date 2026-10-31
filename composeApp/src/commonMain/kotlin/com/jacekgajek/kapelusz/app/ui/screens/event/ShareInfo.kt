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
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.info_date
import com.jacekgajek.kapelusz.app.generated.info_lineup
import com.jacekgajek.kapelusz.app.generated.info_map
import com.jacekgajek.kapelusz.app.generated.info_place
import com.jacekgajek.kapelusz.app.generated.info_plan
import com.jacekgajek.kapelusz.app.generated.share_info
import com.jacekgajek.kapelusz.app.generated.share_info_copied
import com.jacekgajek.kapelusz.app.generated.share_info_failed
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.platform.ShareResult
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.app.ui.text.displayLabel
import com.jacekgajek.kapelusz.app.ui.text.formatAgendaTime
import com.jacekgajek.kapelusz.app.ui.text.setTimes
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.text.EventLinks
import com.jacekgajek.kapelusz.text.Places
import com.jacekgajek.kapelusz.view.partyName
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The labels of the shared gig info, in the app's language. */
class InfoWords(val date: String, val place: String, val map: String, val plan: String, val lineup: String, val footer: String)

/**
 * The gig to send to someone: its name; date, place and the map pin; the plan, a time per line; the
 * notes as they are; the line-up on one line; the app's address last, as under a shared settlement. Parts apart by a
 * blank line; what the event does not have is left out.
 */
fun eventInfoText(event: Event, plan: List<String>, lineup: List<String>, words: InfoWords, language: String): String {
    // As everywhere in the app: 07.11.2026 in Polish, 07 Nov 2026 in English (07.11 would read as July 11 there).
    val date = formatDate(event.date, language)
    val where = listOfNotNull(
        "${words.date}: $date",
        event.location?.takeIf { it.isNotBlank() }?.let { "${words.place}: ${it.trim()}" },
        // Only an exact pin: a search link made from a place name would be long and no better than the name.
        // Directions to the location, an address or a pin.
        Places.navigateUrl(event)?.let { "${words.map}: $it" },
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
    val share = LocalPlatform.current.sharing.rememberShare()
    val scope = rememberCoroutineScope()
    val lang = language
    val words = InfoWords(
        stringResource(Res.string.info_date),
        stringResource(Res.string.info_place),
        stringResource(Res.string.info_map),
        stringResource(Res.string.info_plan),
        stringResource(Res.string.info_lineup),
        // The event's own link, opening it in the app.
        EventLinks.url(AppConfig.WEB_URL, event.id),
    )
    val plan = event.setTimes().map { "${it.displayLabel()} ${formatAgendaTime(it)}" }
    val lineup = SettlementCalculator.partyOrder(band, event).map { partyName(band, event, it, "") }
    val copied = stringResource(Res.string.share_info_copied)
    val failed = stringResource(Res.string.share_info_failed)
    return {
        val text = eventInfoText(event, plan, lineup, words, lang)
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
