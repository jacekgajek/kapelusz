package com.jacekgajek.kapelusz.app.intents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.attachments.Attachments
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.bands.LinkedEvent
import com.jacekgajek.kapelusz.app.bands.linkedEvent
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.event_link_no_access
import com.jacekgajek.kapelusz.app.generated.event_link_offline
import com.jacekgajek.kapelusz.app.generated.event_link_opening
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import com.jacekgajek.kapelusz.app.ui.screens.JoinDialog
import com.jacekgajek.kapelusz.app.ui.screens.share.SharedFilesDialog
import com.jacekgajek.kapelusz.app.util.newId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The only consumer of the [IntentInbox] (REFACTORING.md 3.6): what the app was asked to do is done here, once it can
 * (signed in, which is where this is composed; for shared files, with a band open), through the [BandNavigator]. The
 * dialogs on the way (join, "Opening the event…", "no access", "Attach to…") are this one's.
 */
@Composable
internal fun IntentRouter(runtime: AppRuntime, bandState: BandState, navigator: BandNavigator) {
    val inbox = runtime.intents
    val pending by inbox.pending.collectAsState()
    // Kept here, not in the opening: taking the intent ends that, and the reason must stay until read.
    var failed by remember { mutableStateOf<LinkedEvent?>(null) }
    pending.filterIsInstance<AppIntent.Join>().firstOrNull()?.let { join ->
        JoinDialog(
            membership = runtime.membership,
            initialCode = join.code,
            onJoined = {
                inbox.take(join)
                // Joined from inside a band: its screens would come back otherwise. From the first screen there are none yet.
                if (bandState is BandState.Ready) navigator.eventList()
            },
            onDismiss = { inbox.take(join) },
        )
    }
    pending.filterIsInstance<AppIntent.OpenEvent>().firstOrNull()?.let { open ->
        EventOpening(runtime, open, navigator, onFail = { failed = it })
    }
    val ready = bandState as? BandState.Ready
    pending.filterIsInstance<AppIntent.ShareFiles>().firstOrNull()?.let { share ->
        if (ready != null) {
            SharedFilesDialog(share.files, runtime.bands, ready, onChoose = {
                inbox.take(share)
                inbox.offer(it)
            }, onDismiss = { inbox.take(share) })
        }
    }
    failed?.let { reason ->
        AlertDialog(
            onDismissRequest = { failed = null },
            text = {
                val offline = reason == LinkedEvent.Unreachable
                Text(stringResource(if (offline) Res.string.event_link_offline else Res.string.event_link_no_access))
            },
            confirmButton = { TextButton(onClick = { failed = null }) { Text(stringResource(Res.string.ok)) } },
        )
    }
}

/**
 * Opens [open]'s event, switching band if needed. An event link (I5 §10) first finds its band among the user's:
 * "Opening the event…" while they are looked through (a new device downloads them first), and "no access" when none of
 * them has it for the user. Files to attach are attached once the band is open, before its screens are set up.
 */
@Composable
private fun EventOpening(runtime: AppRuntime, open: AppIntent.OpenEvent, navigator: BandNavigator, onFail: (LinkedEvent) -> Unit) {
    val inbox = runtime.intents
    val failed by rememberUpdatedState(onFail)
    LaunchedEffect(open) {
        val bandId = open.bandId ?: when (val found = runtime.linkedEvent(open.eventId)) {
            is LinkedEvent.Found -> found.bandId

            else -> {
                failed(found)
                null
            }
        }
        // The band can be gone by the time it opens (deleted, access lost): then nothing more happens.
        if (bandId != null && navigator.openBand(bandId, forScreens = true)) {
            when (val carry = open.carry?.let { if (it is Carry.Attach) attach(runtime.attachments, it) else it }) {
                is Carry.NewCost -> navigator.showNewCost(open.eventId, inbox.hold(carry))
                else -> navigator.showEvent(open.eventId, open.section, carry?.let(inbox::hold))
            }
        }
        // Last: taking it ends this.
        inbox.take(open)
    }
    if (open.bandId == null) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(Res.string.event_link_opening))
                }
            },
            confirmButton = { TextButton(onClick = { inbox.take(open) }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/**
 * Attaches the shared files to their owner in the open band; what could not be attached is counted. Whole: cut short
 * (a newer intent, the app signed out), some files attached without the event's Undo would be the worst of both.
 */
private suspend fun attach(fileStore: Attachments, carry: Carry.Attach): Carry.Attached = withContext(NonCancellable) {
    val attached = carry.files.mapNotNull { file -> newId().takeIf { id -> fileStore.attach(carry.owner, file, id) == null } }
    Carry.Attached(carry.owner, attached, failed = carry.files.size - attached.size)
}
