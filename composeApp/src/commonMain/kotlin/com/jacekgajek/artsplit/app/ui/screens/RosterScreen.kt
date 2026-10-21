package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.jacekgajek.artsplit.api.InviteInfo
import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.add_member
import com.jacekgajek.artsplit.app.generated.archive
import com.jacekgajek.artsplit.app.generated.archived_member
import com.jacekgajek.artsplit.app.generated.change_own_role_confirm
import com.jacekgajek.artsplit.app.generated.change_own_role_text
import com.jacekgajek.artsplit.app.generated.change_own_role_title
import com.jacekgajek.artsplit.app.generated.close
import com.jacekgajek.artsplit.app.generated.create_invite
import com.jacekgajek.artsplit.app.generated.invite_active_until
import com.jacekgajek.artsplit.app.generated.invite_copied
import com.jacekgajek.artsplit.app.generated.invite_failed
import com.jacekgajek.artsplit.app.generated.invite_hint
import com.jacekgajek.artsplit.app.generated.invite_role
import com.jacekgajek.artsplit.app.generated.invites_unavailable
import com.jacekgajek.artsplit.app.generated.make_owner
import com.jacekgajek.artsplit.app.generated.make_owner_confirm
import com.jacekgajek.artsplit.app.generated.member_name
import com.jacekgajek.artsplit.app.generated.menu_roster
import com.jacekgajek.artsplit.app.generated.move_down
import com.jacekgajek.artsplit.app.generated.move_up
import com.jacekgajek.artsplit.app.generated.not_linked
import com.jacekgajek.artsplit.app.generated.revoke_invite
import com.jacekgajek.artsplit.app.generated.role
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.generated.share_invite
import com.jacekgajek.artsplit.app.generated.share_invite_text
import com.jacekgajek.artsplit.app.generated.unarchive
import com.jacekgajek.artsplit.app.generated.unlink
import com.jacekgajek.artsplit.app.generated.unlink_confirm
import com.jacekgajek.artsplit.app.platform.ShareResult
import com.jacekgajek.artsplit.app.platform.rememberShare
import com.jacekgajek.artsplit.app.ui.components.Actions
import com.jacekgajek.artsplit.app.ui.components.Avatar
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.ConfirmDialog
import com.jacekgajek.artsplit.app.ui.components.Dropdown
import com.jacekgajek.artsplit.app.ui.components.TextInputDialog
import com.jacekgajek.artsplit.app.ui.components.label
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.components.rememberActions
import com.jacekgajek.artsplit.app.ui.model.Can
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.MoveMember
import com.jacekgajek.artsplit.ops.RenameMember
import com.jacekgajek.artsplit.ops.SetMemberArchived
import com.jacekgajek.artsplit.ops.SetMemberRole
import com.jacekgajek.artsplit.ops.TransferOwnership
import com.jacekgajek.artsplit.ops.UnlinkUser
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Roles an admin can give; ownership only changes by transfer. */
private val ASSIGNABLE_ROLES = listOf(Role.VIEWER, Role.MEMBER, Role.LEAD, Role.ADMIN)

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
            runCatching { graph.api.invites(band.id).invites }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (isAdmin && invitesFailed) {
                item { Text(stringResource(Res.string.invites_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(members, key = { _, member -> member.id }) { index, member ->
                val invite = invites?.firstOrNull { it.memberId == member.id }
                ListItem(
                    modifier = Modifier.clickable(enabled = isAdmin) { selected = member.id },
                    leadingContent = { Avatar(member.name, 40.dp) },
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
                                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.move_up))
                                }
                                IconButton(onClick = { actions.run(MoveMember(member.id, index + 1)) }, enabled = index < members.lastIndex) {
                                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.move_down))
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

@Composable
private fun MemberDialog(
    graph: AppGraph,
    band: Band,
    member: Member,
    myUserId: String,
    invitesLoaded: Boolean,
    invite: InviteInfo?,
    can: Can,
    actions: Actions,
    onInvitesChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val share = rememberShare()
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
                    Dropdown(member.role, ASSIGNABLE_ROLES, { role ->
                        // Changing your own role can take away the rights you are using right now: ask first.
                        if (isMe && role != member.role) confirmOwnRole = role else actions.run(SetMemberRole(member.id, role))
                    }, stringResource(Res.string.role)) { it.label() }
                }
                if (member.userId == null && !member.archived) {
                    HorizontalDivider()
                    val current = shownInvite
                    if (current != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
                                Text("${current.role.label()} · ${stringResource(Res.string.invite_active_until, current.code, expiryText(current))}", style = MaterialTheme.typography.bodySmall)
                                val text = stringResource(Res.string.share_invite_text, band.name, current.code, "${AppConfig.WEB_URL}/join/${current.code}")
                                val copied = stringResource(Res.string.invite_copied)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = {
                                        scope.launch { if (share(text) == ShareResult.COPIED) message = copied }
                                    }) { Text(stringResource(Res.string.share_invite)) }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            runCatching { graph.api.revokeInvite(band.id, current.code) }
                                                .onSuccess { shownInvite = null; onInvitesChanged() }
                                                .onFailure { message = getString(Res.string.invite_failed) }
                                        }
                                    }) { Text(stringResource(Res.string.revoke_invite)) }
                                }
                            }
                        }
                    } else if (!shownInviteKnown) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(Res.string.invite_hint), style = MaterialTheme.typography.bodySmall)
                        Dropdown(inviteRole, ASSIGNABLE_ROLES, { inviteRole = it }, stringResource(Res.string.invite_role)) { it.label() }
                        FilledTonalButton(onClick = {
                            scope.launch {
                                runCatching { graph.api.createInvite(band.id, member.id, inviteRole) }
                                    .onSuccess { shownInvite = it; onInvitesChanged() }
                                    .onFailure { message = getString(Res.string.invite_failed) }
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
