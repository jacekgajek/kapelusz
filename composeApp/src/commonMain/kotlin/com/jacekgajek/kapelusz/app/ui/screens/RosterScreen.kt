package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.ShareEditor
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.settle_together_title
import com.jacekgajek.kapelusz.app.generated.role_row
import com.jacekgajek.kapelusz.app.generated.add_member
import com.jacekgajek.kapelusz.app.generated.archive
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.change_own_role_confirm
import com.jacekgajek.kapelusz.app.generated.change_own_role_text
import com.jacekgajek.kapelusz.app.generated.change_own_role_title
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.copy_link
import com.jacekgajek.kapelusz.app.generated.create_invite
import com.jacekgajek.kapelusz.app.generated.invite_active_until
import com.jacekgajek.kapelusz.app.generated.invite_copied
import com.jacekgajek.kapelusz.app.generated.invite_failed
import com.jacekgajek.kapelusz.app.generated.invite_hint
import com.jacekgajek.kapelusz.app.generated.invite_role
import com.jacekgajek.kapelusz.app.generated.invites_unavailable
import com.jacekgajek.kapelusz.app.generated.link_copied
import com.jacekgajek.kapelusz.app.generated.make_owner
import com.jacekgajek.kapelusz.app.generated.make_owner_confirm
import com.jacekgajek.kapelusz.app.generated.member_name
import com.jacekgajek.kapelusz.app.generated.member_share_apply_no
import com.jacekgajek.kapelusz.app.generated.member_share_apply_text
import com.jacekgajek.kapelusz.app.generated.member_share_apply_title
import com.jacekgajek.kapelusz.app.generated.member_share_apply_yes
import com.jacekgajek.kapelusz.app.generated.member_share_fixed
import com.jacekgajek.kapelusz.app.generated.member_share_hint
import com.jacekgajek.kapelusz.app.generated.member_share_line
import com.jacekgajek.kapelusz.app.generated.member_share_title
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.move_down
import com.jacekgajek.kapelusz.app.generated.move_up
import com.jacekgajek.kapelusz.app.generated.not_linked
import com.jacekgajek.kapelusz.app.generated.reject_holds_money
import com.jacekgajek.kapelusz.app.generated.reject_viewer_still_plays
import com.jacekgajek.kapelusz.app.generated.revoke_invite
import com.jacekgajek.kapelusz.app.generated.role
import com.jacekgajek.kapelusz.app.generated.roster_archived
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.share_invite
import com.jacekgajek.kapelusz.app.generated.share_invite_text
import com.jacekgajek.kapelusz.app.generated.unarchive
import com.jacekgajek.kapelusz.app.generated.unlink
import com.jacekgajek.kapelusz.app.generated.unlink_confirm
import com.jacekgajek.kapelusz.app.platform.ShareResult
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.app.platform.rememberShare
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.RolePicker
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberShareEditorState
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.domain.MemberShares
import com.jacekgajek.kapelusz.domain.Viewers
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.MoveMember
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UnlinkUser
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/** The band roster. Leaders manage it here (roles, invites, links); others see it read-only. */
@Composable
fun RosterScreen(graph: AppGraph, state: BandState.Ready, onBack: () -> Unit) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val can = Can(band, state.userId)
    val isAdmin = can.manageRoster()
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var invites by remember { mutableStateOf<List<InviteInfo>?>(null) }
    var invitesFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reloadInvites: () -> Unit = {
        scope.launch {
            runSuspendCatching { graph.api.invites(band.id).invites }
                .onSuccess { invites = it; invitesFailed = false }
                .onFailure { invitesFailed = true }
        }
    }
    LaunchedEffect(band.id, isAdmin) { if (isAdmin) reloadInvites() }
    // Archived members below, in a section of their own: bands that change players collect many of them.
    val (archived, active) = band.members.partition { it.archived }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    // Leads set default shares (ITERATION_4 §2.3); leaders also manage the rest of a member in their dialog.
    val canShare = can.setMemberShare()
    var sharing by remember { mutableStateOf<String?>(null) }
    // Everyone else gets a smaller dialog: the default share for leads, settling together (ITERATION_5 §7) for all.
    val me = state.me
    val open: (Member) -> (() -> Unit)? = { member ->
        val pair = me != null && hasSettleTogether(band, me, member)
        if (isAdmin || (!member.archived && (canShare || pair))) ({ selected = member.id }) else null
    }

    Scaffold(
        topBar = { BackTopBar(stringResource(Res.string.menu_roster), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (isAdmin) ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.add_member)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).listWidth()) {
            if (isAdmin && invitesFailed) {
                item { Text(stringResource(Res.string.invites_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(active, key = { _, member -> member.id }) { index, member ->
                // Moves are by roster position, which counts archived members too: next to the neighbour above or below.
                val up = active.getOrNull(index - 1)?.let { band.members.indexOf(it) }
                val down = active.getOrNull(index + 1)?.let { band.members.indexOf(it) }
                val moves: @Composable () -> Unit = {
                    IconButton(onClick = { up?.let { actions.run(MoveMember(member.id, it)) } }, enabled = up != null) {
                        Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.move_up) + ": " + member.name)
                    }
                    IconButton(onClick = { down?.let { actions.run(MoveMember(member.id, it)) } }, enabled = down != null) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.move_down) + ": " + member.name)
                    }
                }
                MemberRow(
                    member,
                    invites?.firstOrNull { it.memberId == member.id },
                    band.currency,
                    onClick = open(member),
                    moves = moves.takeIf { isAdmin },
                    isMyPartner = me != null && member.id == me.settlesWith,
                )
            }
            if (archived.isNotEmpty()) {
                // Collapsed at first: only a count, so a long list of past players doesn't bury the band.
                item(key = "archived-title") {
                    ListItem(
                        modifier = Modifier.clickable { showArchived = !showArchived },
                        headlineContent = { Text(stringResource(Res.string.roster_archived, archived.size), style = MaterialTheme.typography.titleMedium) },
                        trailingContent = { Icon(if (showArchived) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                    )
                }
                if (showArchived) items(archived, key = { it.id }) { member ->
                    MemberRow(member, invites?.firstOrNull { it.memberId == member.id }, band.currency, onClick = open(member))
                }
            }
        }
    }

    if (adding) {
        TextInputDialog(
            stringResource(Res.string.add_member),
            stringResource(Res.string.member_name),
            "",
            onConfirm = { actions.run(AddMember(newId(), it)) },
            onDismiss = { adding = false },
        )
    }
    selected?.let { id -> band.member(id) }?.takeIf { !isAdmin }?.let { member ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(member.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canShare) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.member_share_line, shareText(member, band.currency)), Modifier.weight(1f))
                        EditButton(onClick = { sharing = member.id })
                    }
                    if (me != null && hasSettleTogether(band, me, member)) SettleTogetherSection(band, me, member, actions)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(Res.string.close)) } },
        )
    }
    selected?.let { id -> band.member(id) }?.takeIf { isAdmin }?.let { member ->
        MemberDialog(
            graph = graph,
            band = band,
            member = member,
            myUserId = state.userId,
            me = me,
            invitesLoaded = invites != null,
            invitesFailed = invitesFailed,
            invite = invites?.firstOrNull { it.memberId == member.id },
            can = can,
            actions = actions,
            onInvitesChanged = reloadInvites,
            onEditShare = { sharing = member.id }.takeIf { canShare && !member.archived },
            onDismiss = { selected = null },
        )
    }
    sharing?.let { id -> band.member(id) }?.let { member -> MemberShareDialog(band, member, actions, onDismiss = { sharing = null }) }
}

/** "100%" or "300 zł (fixed)": a member's default share (ITERATION_4 §2.3). */
@Composable
private fun shareText(member: Member, currency: String): String =
    member.shareFixed?.let { stringResource(Res.string.member_share_fixed, formatMoney(it, currency, language)) } ?: "${member.shareWeight}%"

/**
 * A member's default share (ITERATION_4 §2.3). Saving it offers to use it in their events not settled yet where they
 * still have the old one; settled and archived events are never changed.
 */
@Composable
private fun MemberShareDialog(band: Band, member: Member, actions: Actions, onDismiss: () -> Unit) {
    val editor = rememberShareEditorState(member.shareWeight, member.shareFixed)
    var applying by remember { mutableStateOf<Pair<Int, Money?>?>(null) }
    // Not on every slider step: it settles each event.
    val stillOld = remember(band, member) { MemberShares.stillDefault(band, member) }
    fun save(weight: Int, fixed: Money?, events: List<Event>) {
        if (actions.run(SetMemberShare(member.id, weight, fixed, events.map { it.id }))) onDismiss()
    }
    val pending = applying
    if (pending == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("${stringResource(Res.string.member_share_title)}: ${member.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareEditor(editor, band.currency)
                    Text(stringResource(Res.string.member_share_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = editor.valid, onClick = {
                    // A fixed share keeps the percentage it had, so switching back restores it (as in events).
                    val fixed = if (editor.fixed) editor.parsedAmount else null
                    val weight = editor.weight
                    when {
                        weight == member.shareWeight && fixed == member.shareFixed -> onDismiss()
                        stillOld.isEmpty() -> save(weight, fixed, emptyList())
                        else -> applying = weight to fixed
                    }
                }) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        )
    } else {
        val (weight, fixed) = pending
        val names = stillOld.take(3).joinToString(", ") { it.name } + if (stillOld.size > 3) "…" else ""
        AlertDialog(
            // Save was pressed already: leaving the question keeps the new default, without touching events.
            onDismissRequest = { save(weight, fixed, emptyList()) },
            title = { Text(stringResource(Res.string.member_share_apply_title)) },
            text = { Text(stringResource(Res.string.member_share_apply_text, member.name, stillOld.size, names)) },
            confirmButton = { TextButton(onClick = { save(weight, fixed, stillOld) }) { Text(stringResource(Res.string.member_share_apply_yes)) } },
            dismissButton = { TextButton(onClick = { save(weight, fixed, emptyList()) }) { Text(stringResource(Res.string.member_share_apply_no)) } },
        )
    }
}

/** A roster row; [moves] are the leader's up/down buttons (only for active members). */
@Composable
private fun MemberRow(
    member: Member,
    invite: InviteInfo?,
    currency: String,
    onClick: (() -> Unit)?,
    moves: (@Composable () -> Unit)? = null,
    /** You settle together with them (ITERATION_5 §7): only your own partner is marked, nobody else's pair. */
    isMyPartner: Boolean = false,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        // Decorative: the name follows right after it (ITERATION_3 R13).
        leadingContent = { Avatar(member.name, member.pictureUrl, 40.dp, decorative = true) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.name, color = if (member.archived) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                if (isMyPartner) Icon(
                    Icons.Default.Group,
                    stringResource(Res.string.settle_together_title),
                    Modifier.padding(start = 6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            Column {
                Text(if (member.userId != null) member.role.label() else stringResource(Res.string.not_linked))
                if (invite != null) Text(stringResource(Res.string.invite_active_until, invite.code, expiryText(invite)))
                Text(stringResource(Res.string.member_share_line, shareText(member, currency)))
            }
        },
        trailingContent = { if (moves != null) Row { moves() } },
    )
    HorizontalDivider()
}

@Composable
private fun expiryText(invite: InviteInfo): String =
    formatDate(Instant.fromEpochMilliseconds(invite.expiresAt).toLocalDateTime(TimeZone.currentSystemDefault()).date, language)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberDialog(
    graph: AppGraph,
    band: Band,
    member: Member,
    myUserId: String,
    me: Member?,
    invitesLoaded: Boolean,
    invitesFailed: Boolean,
    invite: InviteInfo?,
    can: Can,
    actions: Actions,
    onInvitesChanged: () -> Unit,
    /** Opens the default share (ITERATION_4 §2.3); null for those who may not change it. */
    onEditShare: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val share = rememberShare()
    val copy = rememberCopy()
    var name by remember(member.id) { mutableStateOf(member.name) }
    var inviteRole by remember(member.id) { mutableStateOf(if (member.role == Role.OWNER) Role.MEMBER else member.role) }
    var shownInvite by remember(member.id) { mutableStateOf(invite) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var confirmOwner by remember { mutableStateOf(false) }
    var confirmOwnRole by remember { mutableStateOf<Role?>(null) }
    var pickingRole by remember { mutableStateOf(false) }
    var confirmRole by remember { mutableStateOf<Role?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    val isOwner = member.role == Role.OWNER
    val isMe = member.userId == myUserId
    // An invite created before the list has loaded would silently replace a code that may already have been shared.
    var shownInviteKnown by remember(member.id) { mutableStateOf(invitesLoaded) }
    LaunchedEffect(invitesLoaded, invite) {
        if (invitesLoaded && !shownInviteKnown) {
            shownInvite = invite
            shownInviteKnown = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.member_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank() && name.trim() != member.name, onClick = { actions.run(RenameMember(member.id, name)) }) {
                        Text(stringResource(Res.string.save))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.member_share_line, shareText(member, band.currency)), Modifier.weight(1f))
                    if (onEditShare != null) EditButton(onClick = onEditShare)
                }
                // Without an account the role only takes effect when they join, which the invite's role decides. A linked
                // member's role is changed rarely: one row, the choices behind it.
                if (!isOwner && member.userId != null) {
                    Row(Modifier.fillMaxWidth().clickable { pickingRole = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.role_row, member.role.label()), Modifier.weight(1f))
                        EditButton(stringResource(Res.string.role), onClick = { pickingRole = true })
                    }
                }
                if (me != null && hasSettleTogether(band, me, member)) SettleTogetherSection(band, me, member, actions)
                if (member.userId == null && !member.archived) {
                    HorizontalDivider()
                    val current = shownInvite
                    // In the app's language (a non-composable getString would use the browser's on the web).
                    val failedText = stringResource(Res.string.invite_failed)
                    val holdsMoneyText = stringResource(Res.string.reject_holds_money)
                    val stillPlaysText = stringResource(Res.string.reject_viewer_still_plays)
                    var inviteBusy by remember { mutableStateOf(false) }
                    if (current != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
                                Text("${current.role.label()} · ${stringResource(Res.string.invite_active_until, current.code, expiryText(current))}", style = MaterialTheme.typography.bodySmall)
                                val link = "${AppConfig.WEB_URL}/join/${current.code}"
                                val text = stringResource(Res.string.share_invite_text, band.name, current.code, link)
                                val copied = stringResource(Res.string.invite_copied)
                                val linkCopied = stringResource(Res.string.link_copied)
                                Text(link, style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = {
                                        scope.launch { if (share(text) == ShareResult.COPIED) message = copied }
                                    }) { Text(stringResource(Res.string.share_invite)) }
                                    OutlinedButton(onClick = {
                                        scope.launch { message = if (copy(link)) linkCopied else failedText }
                                    }) { Text(stringResource(Res.string.copy_link)) }
                                    OutlinedButton(enabled = !inviteBusy, onClick = {
                                        inviteBusy = true
                                        scope.launch {
                                            runSuspendCatching { graph.api.revokeInvite(band.id, current.code) }
                                                .onSuccess { shownInvite = null; onInvitesChanged() }
                                                .onFailure { message = failedText }
                                            inviteBusy = false
                                        }
                                    }) { Text(stringResource(Res.string.revoke_invite)) }
                                }
                            }
                        }
                    } else if (invitesFailed) {
                        Text(stringResource(Res.string.invites_unavailable), color = MaterialTheme.colorScheme.error)
                    } else if (!shownInviteKnown) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(Res.string.invite_hint), style = MaterialTheme.typography.bodySmall)
                        RolePicker(inviteRole, { inviteRole = it }, stringResource(Res.string.invite_role))
                        // Once at a time: two quick taps would make two codes, and the one shown could be the replaced one.
                        FilledTonalButton(enabled = !inviteBusy, onClick = {
                            // Someone holding band money cannot become an artist (ITERATION_4 §1.1).
                            if (inviteRole == Role.ARTIST && Artists.holdsMoney(band, member.id)) {
                                message = holdsMoneyText
                                return@FilledTonalButton
                            }
                            // Nor a viewer while playing in an unsettled event or holding the fund (V5-M1 B2).
                            if (inviteRole == Role.VIEWER && !Viewers.mayBecome(band, member.id)) {
                                message = stillPlaysText
                                return@FilledTonalButton
                            }
                            inviteBusy = true
                            scope.launch {
                                runSuspendCatching { graph.api.createInvite(band.id, member.id, inviteRole) }
                                    .onSuccess { shownInvite = it; onInvitesChanged() }
                                    .onFailure { message = failedText }
                                inviteBusy = false
                            }
                        }) { Text(stringResource(Res.string.create_invite)) }
                    }
                }
                // Unlinking your own account would lock you out of the band.
                if (member.userId != null && !isOwner && !isMe) {
                    OutlinedButton(onClick = { confirmUnlink = true }) { Text(stringResource(Res.string.unlink)) }
                }
                if (can.transferOwnership() && member.userId != null && member.role == Role.LEAD) {
                    OutlinedButton(onClick = { confirmOwner = true }) { Text(stringResource(Res.string.make_owner)) }
                }
                // Not yourself: an archived member can do nothing in the band, so you could not undo it (V5-M1 C5).
                if (!isOwner && !isMe) {
                    OutlinedButton(onClick = {
                        // Archiving ends their settling together (ITERATION_5 §7): said first.
                        if (!member.archived && member.settlesWith != null) confirmArchive = true
                        else actions.run(SetMemberArchived(member.id, !member.archived))
                    }) {
                        Text(stringResource(if (member.archived) Res.string.unarchive else Res.string.archive))
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )

    if (confirmUnlink) {
        ConfirmDialog(
            title = stringResource(Res.string.unlink),
            text = listOfNotNull(stringResource(Res.string.unlink_confirm, member.name), pairEndsWarning(band, member)).joinToString("\n\n"),
            confirmLabel = stringResource(Res.string.unlink),
            onConfirm = { actions.run(UnlinkUser(member.id)) },
            onDismiss = { confirmUnlink = false },
        )
    }
    if (pickingRole) {
        AlertDialog(
            onDismissRequest = { pickingRole = false },
            title = { Text(member.name) },
            text = {
                RolePicker(member.role, { role ->
                    pickingRole = false
                    when {
                        role == member.role -> {}
                        // Changing your own role can take away the rights you are using right now: ask first.
                        isMe -> confirmOwnRole = role
                        // So does a change that ends their settling together (ITERATION_5 §7).
                        pairEndsWarningNeeded(member, role) -> confirmRole = role
                        else -> actions.run(SetMemberRole(member.id, role))
                    }
                }, stringResource(Res.string.role), Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { pickingRole = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    confirmRole?.let { role ->
        ConfirmDialog(
            title = stringResource(Res.string.role_row, role.label()),
            text = pairEndsWarning(band, member, role),
            confirmLabel = stringResource(Res.string.save),
            onConfirm = { actions.run(SetMemberRole(member.id, role)) },
            onDismiss = { confirmRole = null },
        )
    }
    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(Res.string.archive),
            text = pairEndsWarning(band, member),
            confirmLabel = stringResource(Res.string.archive),
            onConfirm = { actions.run(SetMemberArchived(member.id, true)) },
            onDismiss = { confirmArchive = false },
        )
    }
    confirmOwnRole?.let { role ->
        ConfirmDialog(
            title = stringResource(Res.string.change_own_role_title),
            text = listOfNotNull(stringResource(Res.string.change_own_role_text, role.label()), pairEndsWarning(band, member, role)).joinToString("\n\n"),
            confirmLabel = stringResource(Res.string.change_own_role_confirm),
            onConfirm = { actions.run(SetMemberRole(member.id, role)) },
            onDismiss = { confirmOwnRole = null },
        )
    }
    if (confirmOwner) {
        ConfirmDialog(
            title = stringResource(Res.string.make_owner),
            text = stringResource(Res.string.make_owner_confirm, member.name),
            confirmLabel = stringResource(Res.string.make_owner),
            onConfirm = { if (actions.run(TransferOwnership(member.id))) onDismiss() },
            onDismiss = { confirmOwner = false },
        )
    }
}
