package com.jacekgajek.kapelusz.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.jacekgajek.kapelusz.app.generated.add_member
import com.jacekgajek.kapelusz.app.generated.archive
import com.jacekgajek.kapelusz.app.generated.archived_member
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
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.move_down
import com.jacekgajek.kapelusz.app.generated.move_up
import com.jacekgajek.kapelusz.app.generated.not_linked
import com.jacekgajek.kapelusz.app.generated.revoke_invite
import com.jacekgajek.kapelusz.app.generated.role
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
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.MoveMember
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UnlinkUser
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/** The band roster. Admins manage it here (roles, invites, links); others see it read-only. */
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
    val members = band.members

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
            itemsIndexed(members, key = { _, member -> member.id }) { index, member ->
                val invite = invites?.firstOrNull { it.memberId == member.id }
                ListItem(
                    modifier = Modifier.clickable(enabled = isAdmin) { selected = member.id },
                    // Decorative: the name follows right after it (ITERATION_3 R13).
                    leadingContent = { Avatar(member.name, member.pictureUrl, 40.dp, decorative = true) },
                    headlineContent = {
                        Text(member.name, color = if (member.archived) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                    },
                    supportingContent = {
                        Column {
                            Text(if (member.userId != null) member.role.label() else stringResource(Res.string.not_linked))
                            if (invite != null) Text(stringResource(Res.string.invite_active_until, invite.code, expiryText(invite)))
                            if (member.archived) Text(stringResource(Res.string.archived_member))
                        }
                    },
                    trailingContent = {
                        if (isAdmin) {
                            Row {
                                IconButton(onClick = { actions.run(MoveMember(member.id, index - 1)) }, enabled = index > 0) {
                                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.move_up) + ": " + member.name)
                                }
                                IconButton(onClick = { actions.run(MoveMember(member.id, index + 1)) }, enabled = index < members.lastIndex) {
                                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.move_down) + ": " + member.name)
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()
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
    selected?.let { id -> band.member(id) }?.let { member ->
        MemberDialog(
            graph = graph,
            band = band,
            member = member,
            myUserId = state.userId,
            invitesLoaded = invites != null,
            invitesFailed = invitesFailed,
            invite = invites?.firstOrNull { it.memberId == member.id },
            can = can,
            actions = actions,
            onInvitesChanged = reloadInvites,
            onDismiss = { selected = null },
        )
    }
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
    invitesLoaded: Boolean,
    invitesFailed: Boolean,
    invite: InviteInfo?,
    can: Can,
    actions: Actions,
    onInvitesChanged: () -> Unit,
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
                // Without an account the role only takes effect when they join, which the invite's role decides.
                if (!isOwner && member.userId != null) {
                    RolePicker(member.role, { role ->
                        // Changing your own role can take away the rights you are using right now: ask first.
                        if (isMe && role != member.role) confirmOwnRole = role else if (role != member.role) actions.run(SetMemberRole(member.id, role))
                    }, stringResource(Res.string.role))
                }
                if (member.userId == null && !member.archived) {
                    HorizontalDivider()
                    val current = shownInvite
                    // In the app's language (a non-composable getString would use the browser's on the web).
                    val failedText = stringResource(Res.string.invite_failed)
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
                if (can.transferOwnership() && member.userId != null && member.role == Role.ADMIN) {
                    OutlinedButton(onClick = { confirmOwner = true }) { Text(stringResource(Res.string.make_owner)) }
                }
                if (!isOwner) {
                    OutlinedButton(onClick = { actions.run(SetMemberArchived(member.id, !member.archived)) }) {
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
            text = stringResource(Res.string.unlink_confirm, member.name),
            confirmLabel = stringResource(Res.string.unlink),
            onConfirm = { actions.run(UnlinkUser(member.id)) },
            onDismiss = { confirmUnlink = false },
        )
    }
    confirmOwnRole?.let { role ->
        ConfirmDialog(
            title = stringResource(Res.string.change_own_role_title),
            text = stringResource(Res.string.change_own_role_text, role.label()),
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
