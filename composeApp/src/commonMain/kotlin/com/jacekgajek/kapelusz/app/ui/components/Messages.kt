package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.sync_refused
import com.jacekgajek.kapelusz.model.RejectionReason
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Something to tell the user, typed: resolved to text only where it is shown ([MessageHost]), in the app's language,
 * so a click handler never needs a string in hand.
 */
sealed interface UiMessage {
    /** A refused change, by its reason. */
    data class Rejected(val reason: RejectionReason) : UiMessage

    /** A sentence from the resources, with its format arguments. */
    data class Text(val res: StringResource, val args: List<Any> = emptyList()) : UiMessage

    /** Text already in hand, e.g. the result of a copy or a share. */
    data class Plain(val text: String) : UiMessage

    /** A change the server refused during sync, e.g. a role changed meanwhile. */
    data class SyncRefused(val reason: RejectionReason) : UiMessage

    /** An Undo snackbar's text; see [UndoStack]. */
    data class Undoable(val text: UiMessage, val undo: () -> Unit, val gone: () -> Unit) : UiMessage
}

/**
 * The one place a screen's messages go: its snackbar, its Undo snackbars, or, while a dialog is open on top of it,
 * the dialog's own notice ([DialogNotice]). One per screen scaffold, from [rememberMessages]; sections and dialogs
 * reach it through the screen's [Actions].
 */
@Stable
class Messages {
    val snackbar = SnackbarHostState()
    val undos = UndoStack()

    /** Open dialogs showing messages themselves, newest last: a dialog hides the screen's snackbar. */
    internal val dialogs = mutableListOf<(UiMessage) -> Unit>()

    /** Plain messages waiting to be shown, oldest first; [MessageHost] resolves the first in composition and shows it. */
    internal val queue = mutableStateListOf<Queued>()
    private var nextId = 0

    /** A queued message with an identity of its own: two equal messages in a row are still two. */
    internal class Queued(val id: Int, val message: UiMessage)

    fun post(message: UiMessage) {
        when (message) {
            is UiMessage.Undoable -> undos.add(UndoItem(message.text, message.undo, message.gone))
            else -> dialogs.lastOrNull()?.invoke(message) ?: queue.add(Queued(nextId++, message))
        }
    }

    fun post(res: StringResource, vararg args: Any) = post(UiMessage.Text(res, args.toList()))

    fun post(text: String) = post(UiMessage.Plain(text))
}

@Composable
fun rememberMessages(): Messages = remember { Messages() }

/** The snackbar slot of a screen: Undo snackbars above the plain ones. */
@Composable
fun MessageHost(messages: Messages) {
    // Resolved while composing (the app's language), shown from an effect, one at a time.
    val next = messages.queue.firstOrNull()
    val text = next?.let { messageTexts().resolve(it.message) }
    LaunchedEffect(next?.id) {
        if (next != null && text != null) {
            messages.snackbar.showSnackbar(text)
            messages.queue.remove(next)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        UndoSnackbars(messages.undos)
        SnackbarHost(messages.snackbar)
    }
}

/** Inside a dialog: what is posted while it is open is shown here rather than in the snackbar behind it, for a while. */
@Composable
fun DialogNotice(messages: Messages) {
    val texts = messageTexts()
    var message by remember { mutableStateOf<UiMessage?>(null) }
    DisposableEffect(messages) {
        val show = { m: UiMessage -> message = m }
        messages.dialogs += show
        onDispose { messages.dialogs -= show }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(DIALOG_NOTICE_MS)
            message = null
        }
    }
    message?.let {
        // A refusal in the error colour; a confirmation (copied, shared) in the primary one.
        val refused = it is UiMessage.Rejected || it is UiMessage.SyncRefused
        val color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Text(texts.resolve(it), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val DIALOG_NOTICE_MS = 8_000L

/** Message texts in the app's language, resolved during composition (a non-composable getString would use the browser's on the web). */
@Composable
internal fun messageTexts(): MessageTexts {
    val reasons = RejectionReason.entries.associateWith { stringResource(it.labelRes()) }
    val refused = stringResource(Res.string.sync_refused)
    return remember(reasons, refused) { MessageTexts(reasons, refused) }
}

internal class MessageTexts(private val reasons: Map<RejectionReason, String>, private val syncRefused: String) {
    @Composable
    fun resolve(message: UiMessage): String = when (message) {
        is UiMessage.Rejected -> reasons.getValue(message.reason)
        is UiMessage.SyncRefused -> "$syncRefused ${reasons.getValue(message.reason)}"
        is UiMessage.Text -> stringResource(message.res, *message.args.toTypedArray())
        is UiMessage.Plain -> message.text
        is UiMessage.Undoable -> resolve(message.text)
    }
}
