package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.section_collapsed
import com.jacekgajek.kapelusz.app.generated.section_expanded
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_paid
import com.jacekgajek.kapelusz.app.generated.settle_received
import com.jacekgajek.kapelusz.app.generated.status_archived
import com.jacekgajek.kapelusz.app.generated.status_awaiting
import com.jacekgajek.kapelusz.app.generated.status_draft
import com.jacekgajek.kapelusz.app.generated.status_loss
import com.jacekgajek.kapelusz.app.generated.status_open
import com.jacekgajek.kapelusz.app.generated.status_settled
import com.jacekgajek.kapelusz.app.generated.transfer_a11y
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.Settlement
import com.jacekgajek.kapelusz.text.BlikPhone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Language code of the current UI locale (`en`, `pl`), for formatting. */
val language: String @Composable get() = Locale.current.language

@Composable
fun StatusChip(status: EventStatus, archived: Boolean = false, modifier: Modifier = Modifier) {
    val (label, container) = when {
        archived -> Res.string.status_archived to MaterialTheme.colorScheme.surfaceVariant
        status == EventStatus.DRAFT -> Res.string.status_draft to MaterialTheme.colorScheme.surfaceVariant
        status == EventStatus.OPEN -> Res.string.status_open to MaterialTheme.colorScheme.tertiaryContainer
        status == EventStatus.SETTLED -> Res.string.status_settled to MaterialTheme.colorScheme.secondaryContainer
        status == EventStatus.AWAITING -> Res.string.status_awaiting to MaterialTheme.colorScheme.tertiaryContainer
        else -> Res.string.status_loss to MaterialTheme.colorScheme.errorContainer
    }
    // A label, not a button: an AssistChip would be announced (and focusable) as something to tap.
    Surface(modifier, color = container, shape = MaterialTheme.shapes.small) {
        Text(stringResource(label), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** A section folded away until tapped open: an arrow and its [title], the whole row tappable (e.g. Recorded transfers, History). */
@Composable
fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    // A heading to jump to with a screen reader, which also says whether it's open.
    val state = stringResource(if (expanded) Res.string.section_expanded else Res.string.section_collapsed)
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                stateDescription = state
            }
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        // Lets screen reader users jump between the sections of long screens (the event screen).
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * "From → To" for transfers. The arrow is an icon: the web build's bundled font has no arrow glyph.
 */
@Composable
fun TransferParties(from: String, to: String, modifier: Modifier = Modifier) {
    // The arrow is an icon: read the pair as one phrase ("Alice to Bob") instead of two unrelated names.
    val description = stringResource(Res.string.transfer_a11y, from, to)
    // Long names (or "Band fund (treasurer)") wrap onto the next line whole, after the arrow, instead of squeezing the
    // other name into a column of letters.
    FlowRow(modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            Text(from, Modifier.weight(1f, fill = false))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
            )
        }
        Text(to, Modifier.align(Alignment.CenterVertically))
    }
}

/** The app version in a corner (sign-in screen, Settings), so it is easy to tell which build is running. */
@Composable
fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        AppConfig.VERSION_LABEL,
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

/**
 * What settling a suggestion is called for [me]: "Paid" when they pay it, "Received" when they get it, "Settle" for a
 * lead recording others'. It records the same; the word says which way the money went.
 */
fun settleLabel(band: Band, settlement: Settlement, me: Id?, from: Id, to: Id): StringResource = when {
    BlikPhone.onSide(band, settlement, me, from) -> Res.string.settle_paid
    BlikPhone.onSide(band, settlement, me, to) -> Res.string.settle_received
    else -> Res.string.settle
}
