package com.jacekgajek.kapelusz.app.ui.components

import com.jacekgajek.kapelusz.app.data.Changes
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.ui.graphics.vector.ImageVector
import com.jacekgajek.kapelusz.app.generated.former_member
import com.jacekgajek.kapelusz.app.generated.former_member_restore
import com.jacekgajek.kapelusz.app.generated.former_member_restored
import com.jacekgajek.kapelusz.app.generated.section_collapsed
import com.jacekgajek.kapelusz.app.generated.section_expanded
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.app.generated.name_taken
import org.jetbrains.compose.resources.StringResource
import com.jacekgajek.kapelusz.domain.BlikPhone
import com.jacekgajek.kapelusz.domain.Settlement
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.app.generated.settle_paid
import com.jacekgajek.kapelusz.app.generated.settle_received
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.undo_too_late
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.amount_invalid
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.not_in_app_invite
import com.jacekgajek.kapelusz.app.generated.not_in_app_text
import com.jacekgajek.kapelusz.app.generated.edit
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.remove
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_part_hint
import com.jacekgajek.kapelusz.app.generated.settle_part_title
import com.jacekgajek.kapelusz.app.generated.status_archived
import com.jacekgajek.kapelusz.app.generated.status_awaiting
import com.jacekgajek.kapelusz.app.generated.status_draft
import com.jacekgajek.kapelusz.app.generated.status_loss
import com.jacekgajek.kapelusz.app.generated.status_open
import com.jacekgajek.kapelusz.app.generated.status_settled
import com.jacekgajek.kapelusz.app.generated.transfer_a11y
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.initials
import com.jacekgajek.kapelusz.app.ui.theme.MoneyColors
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.Rejection
import com.jacekgajek.kapelusz.ops.RejectionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import org.jetbrains.compose.resources.stringResource

/** Language code of the current UI locale (`en`, `pl`), for formatting. */
val language: String @Composable get() = Locale.current.language

/** Applies operations; a rejection is shown in the snackbar. */
class Actions(
    private val changes: Changes,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val reasonTexts: Map<RejectionReason, String>,
    /** The screen's Undo snackbars; without them [runWithUndo] just runs. */
    private val undos: UndoStack? = null,
    private val tooLate: String = "",
) {
    /** Open dialogs showing messages themselves ([DialogNotice]), newest last: a dialog hides the screen's snackbar. */
    internal val dialogs = mutableListOf<(String) -> Unit>()

    fun run(op: Operation): Boolean = show(changes.dispatch(op))

    /** Shows [rejection] (if any) in the snackbar; true when there was none. */
    fun show(rejection: Rejection?): Boolean {
        if (rejection == null) return true
        say(reasonTexts.getValue(rejection.reason))
        return false
    }

    fun say(message: String) {
        dialogs.lastOrNull()?.let { return it(message) }
        scope.launch { snackbar.showSnackbar(message) }
    }

    /**
     * Runs [op] with Undo in a snackbar saying [message] (ITERATION_5 §2): it is held back from syncing while the
     * snackbar shows, and Undo takes it back as if it was never made.
     */
    fun runWithUndo(op: Operation, message: String): Boolean {
        val stack = undos ?: return run(op)
        return show(
            changes.dispatchHeld(op, stack.timeout + HOLD_MARGIN) { held ->
                val undo = {
                    // Its hold ran out first (e.g. the device slept): it may be on its way, so only the history can undo it.
                    if (!changes.withdraw(held)) {
                        changes.release(held)
                        say(tooLate)
                    }
                }
                stack.add(UndoItem(message, undo = undo, gone = { changes.release(held) }))
            },
        )
    }

    companion object {
        /**
         * A hold lasts this much longer than its snackbar shows: the snackbar ends the hold itself; the hold's own end
         * is for when the app is closed meanwhile.
         */
        const val HOLD_MARGIN = 5_000L
    }
}

/**
 * Inside a dialog: what [actions] say while it is open (e.g. a refusal), shown here rather than in the snackbar behind
 * it, for a while.
 */
@Composable
fun DialogNotice(actions: Actions) {
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(actions) {
        val show = { text: String -> message = text }
        actions.dialogs += show
        onDispose { actions.dialogs -= show }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(DIALOG_NOTICE_MS)
            message = null
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

/** How long a [DialogNotice] stays. */
private const val DIALOG_NOTICE_MS = 8_000L

@Composable
fun rememberActions(changes: Changes, snackbar: SnackbarHostState, undos: UndoStack? = null): Actions {
    val scope = rememberCoroutineScope()
    // Resolved during composition so they follow the in-app language; a non-composable getString() would use the
    // system language on the web.
    val reasonTexts = RejectionReason.entries.associateWith { stringResource(it.labelRes()) }
    val tooLate = stringResource(Res.string.undo_too_late)
    return remember(changes, snackbar, reasonTexts, undos, tooLate) { Actions(changes, snackbar, scope, reasonTexts, undos, tooLate) }
}

@Composable
fun MoneyText(
    money: Money,
    currency: String,
    modifier: Modifier = Modifier,
    colored: Boolean = false,
    bold: Boolean = false,
) {
    val color = when {
        !colored -> Color.Unspecified
        money.isNegative -> MoneyColors.negative
        money.isPositive -> MoneyColors.positive
        else -> Color.Unspecified
    }
    Text(
        formatMoney(money, currency, language),
        modifier,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        maxLines = 1,
    )
}

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

@Composable
fun Avatar(name: String, pictureUrl: String? = null, size: Dp = 28.dp, decorative: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            // Next to the name already (a chip, the account row) it would only be read out twice.
            .clearAndSetSemantics { if (!decorative) contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        // Initials underneath: shown while the photo loads, and instead of it when there is none or it fails.
        Text(
            initials(name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (pictureUrl != null) AsyncImage(pictureUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/**
 * In place of the avatar of a member without an app account, who can't see the band (ITERATION_5 §12). Described for
 * screen readers when [description] is given (when it can be tapped).
 */
@Composable
fun NotInAppIcon(size: Dp = 28.dp, description: String? = null) = AvatarIcon(Icons.Default.VisibilityOff, size, description)

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

/** "Bob cannot see the band. Do you want to invite them?" for a member not in the app; Invite: [onInvite]. */
@Composable
fun InviteMemberDialog(name: String, onInvite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(Res.string.not_in_app_text, name)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onInvite()
            }) { Text(stringResource(Res.string.not_in_app_invite)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** In place of a guest's avatar: someone playing this one event with the band. */
@Composable
fun GuestIcon(size: Dp = 28.dp) = AvatarIcon(Icons.Default.Handshake, size, null)

@Composable
private fun AvatarIcon(icon: ImageVector, size: Dp, description: String?) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.6f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The archived member called [name], when it is someone who used to be in the band. */
fun Band.formerMemberNamed(name: String): Member? = memberNamed(name)?.takeIf { name.isNotBlank() && it.archived }

/**
 * Under a name field, for a name of someone who used to be in the band (archived names stay taken): "Bob used to be in
 * the band", and Welcome back, which restores them; then [onRestored].
 */
@Composable
fun FormerMember(member: Member, actions: Actions, onRestored: () -> Unit) {
    val welcome = stringResource(Res.string.former_member_restored, member.name)
    Column {
        Text(stringResource(Res.string.former_member, member.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            if (actions.run(SetMemberArchived(member.id, false))) {
                onRestored()
                actions.say(welcome)
            }
        }) { Text(stringResource(Res.string.former_member_restore)) }
    }
}

/** A member's name is free: null, or why not (a member already has it; ITERATION_5 §12). */
@Composable
fun nameTakenError(band: Band, name: String, except: String? = null): String? =
    band.memberNamed(name, except)?.takeIf { name.isNotBlank() }?.let { stringResource(Res.string.name_taken, it.name) }

/** Decimal input for amounts; [onValid] receives the parsed amount or null when the text is not a valid amount. */
@Composable
fun MoneyField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    val parsed = parseMoney(text)
    val invalid = text.isNotBlank() && (parsed == null || (!allowNegative && parsed.isNegative))
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(Res.string.amount_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

fun parseMoney(text: String): Money? = runCatching { Money.parse(withoutGrouping(text.filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' })) }.getOrNull()

/**
 * Drops thousands separators in either style (ITERATION_3 R8): "1,000", "1.000", "1,000.50", "1.000,50". Money has at
 * most 2 decimals, so a separator followed by exactly 3 digits, or one that repeats, or the first of two different
 * ones, groups thousands; what is left is the decimal separator.
 */
internal fun withoutGrouping(text: String): String {
    val separators = text.filter { it == ',' || it == '.' }
    if (separators.isEmpty()) return text
    val decimal: Char? = when {
        separators.toSet().size == 2 -> separators.last()
        separators.length > 1 -> null
        Regex("""[.,]\d{3}$""").containsMatchIn(text) -> null
        else -> separators.single()
    }
    return text.filter { it != ',' && it != '.' || it == decimal }
}

@Composable
fun ConfirmDialog(title: String, text: String?, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Dialog with a single text field, e.g. for names; [error] says why a text can't be taken (e.g. a name in use). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    error: @Composable (String) -> String? = { null },
    /** Whether OK is possible besides [error]: e.g. not while [below] offers something else. */
    allowed: (String) -> Boolean = { true },
    /** Under the field. */
    below: @Composable (String) -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    val problem = error(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                below(text)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && problem == null && allowed(text), onClick = { onConfirm(text.trim()); onDismiss() }) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
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

/** Records only part of a suggested transfer, e.g. the cash someone has on them; the rest stays suggested. */
@Composable
fun PartialSettleDialog(
    from: String,
    to: String,
    suggested: Money,
    currency: String,
    onConfirm: (Money) -> Boolean,
    onDismiss: () -> Unit,
    /** As on the suggestion's button: Paid, Received or Settle ([settleLabel]). */
    confirmLabel: StringResource = Res.string.settle,
    /** Whose refusals show in the dialog ([DialogNotice]). */
    actions: Actions? = null,
) {
    val lang = language
    // Empty and focused: the amount handed over is usually known, the suggestion stays in the hint below.
    var amount by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive && it <= suggested }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settle_part_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferParties(from, to)
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount), Modifier.focusRequester(focus))
                Text(
                    stringResource(Res.string.settle_part_hint, formatMoney(suggested, currency, lang)),
                    style = MaterialTheme.typography.bodySmall,
                )
                actions?.let { DialogNotice(it) }
            }
        },
        confirmButton = {
            // Refused (e.g. more than an artist is owed now): the dialog stays, with the amount (V5-M1 C4c).
            TextButton(enabled = parsed != null, onClick = { if (onConfirm(parsed!!)) onDismiss() }) {
                Text(stringResource(confirmLabel))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The widest a list grows: on tablets it stays readable, centred (ITERATION_3 R13). */
val MAX_LIST_WIDTH = 600.dp

/** Caps a list (after its size modifiers) at [MAX_LIST_WIDTH], centred in the space it was given. */
fun Modifier.listWidth(): Modifier = wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = MAX_LIST_WIDTH).fillMaxWidth()

/** Visible edit affordance at the end of a tappable row (touch screens show no hover highlight). */
@Composable
fun EditButton(what: String? = null, onClick: () -> Unit) {
    val edit = stringResource(Res.string.edit)
    // Named after the item where there are several in a list, as RemoveButton is: "Edit: Anna".
    IconButton(onClick = onClick) { Icon(Icons.Default.Edit, if (what.isNullOrBlank()) edit else "$edit: $what") }
}

/** Visible remove affordance at the end of a tappable row; [what] names the item for screen readers (ITERATION_3 R13). */
@Composable
fun RemoveButton(what: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.Delete, removeLabel(what)) }
}

/** "Remove: Anna", or just "Remove" for an item without a name yet (e.g. an empty field). */
@Composable
fun removeLabel(what: String): String {
    val remove = stringResource(Res.string.remove)
    return if (what.isBlank()) remove else "$remove: $what"
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
