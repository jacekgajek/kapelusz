package com.jacekgajek.kapelusz.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_member
import com.jacekgajek.kapelusz.app.generated.archive
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.change_own_role_confirm
import com.jacekgajek.kapelusz.app.generated.change_own_role_text
import com.jacekgajek.kapelusz.app.generated.change_own_role_title
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.copy_link
import com.jacekgajek.kapelusz.app.generated.create_invite
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.delete_member
import com.jacekgajek.kapelusz.app.generated.delete_member_linked
import com.jacekgajek.kapelusz.app.generated.delete_member_text
import com.jacekgajek.kapelusz.app.generated.delete_member_title
import com.jacekgajek.kapelusz.app.generated.deleted_member
import com.jacekgajek.kapelusz.app.generated.invite_active_until
import com.jacekgajek.kapelusz.app.generated.invite_copied
import com.jacekgajek.kapelusz.app.generated.invite_failed
import com.jacekgajek.kapelusz.app.generated.invite_hint
import com.jacekgajek.kapelusz.app.generated.invite_qr
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
import com.jacekgajek.kapelusz.app.generated.member_share_hint
import com.jacekgajek.kapelusz.app.generated.member_share_line
import com.jacekgajek.kapelusz.app.generated.member_share_title
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.generated.not_linked
import com.jacekgajek.kapelusz.app.generated.reject_holds_money
import com.jacekgajek.kapelusz.app.generated.reject_viewer_still_plays
import com.jacekgajek.kapelusz.app.generated.revoke_invite
import com.jacekgajek.kapelusz.app.generated.role
import com.jacekgajek.kapelusz.app.generated.role_row
import com.jacekgajek.kapelusz.app.generated.roster_archived
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.search_people_none
import com.jacekgajek.kapelusz.app.generated.settle_together_title
import com.jacekgajek.kapelusz.app.generated.share_invite
import com.jacekgajek.kapelusz.app.generated.share_invite_text
import com.jacekgajek.kapelusz.app.generated.unarchive
import com.jacekgajek.kapelusz.app.generated.unlink
import com.jacekgajek.kapelusz.app.generated.unlink_confirm
import com.jacekgajek.kapelusz.app.platform.ShareResult
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.app.platform.rememberShare
import com.jacekgajek.kapelusz.app.ui.BackNav
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.FormerMember
import com.jacekgajek.kapelusz.app.ui.components.MemberAvatar
import com.jacekgajek.kapelusz.app.ui.components.NameSearchField
import com.jacekgajek.kapelusz.app.ui.components.QrCode
import com.jacekgajek.kapelusz.app.ui.components.RoleGroup
import com.jacekgajek.kapelusz.app.ui.components.RolePicker
import com.jacekgajek.kapelusz.app.ui.components.ShareEditor
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.formerMemberNamed
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.components.nameTakenError
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.components.rememberShareEditorState
import com.jacekgajek.kapelusz.app.ui.components.shareText
import com.jacekgajek.kapelusz.app.ui.components.sharesText
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.rules.MemberDeletion
import com.jacekgajek.kapelusz.settlement.MemberShares
import com.jacekgajek.kapelusz.view.BandView
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** The band roster. Leaders manage it here (roles, invites, links); others see it read-only. */
@Composable
fun RosterScreen(session: BandSession, nav: BackNav, roster: RosterViewModel, openMember: String? = null) {
    val state = session.state
    val band = state.band
    val messages = rememberMessages()
    // Deleting a member can be undone from its snackbar (ITERATION_7 §3.1).
    val actions = rememberActions(session.changes, messages)
    val can = session.can
    val isAdmin = can.manageRoster()
    // Artists see names only: no roles or shares, only settling together behind a tap (ITERATION_6 §11).
    val namesOnly = can.artist
    LaunchedEffect(band.id, isAdmin) { if (isAdmin) roster.reloadInvites() }
    LaunchedEffect(roster.invites) { roster.invitesLoaded() }
    val routeMember = openMember?.let { band.member(it) }
    LaunchedEffect(routeMember) { roster.openFromRoute(routeMember, state.userId) }
    val (active, archived, searchable) = roster.lists(band)
    val query = roster.query
    // Leads set default shares (ITERATION_4 §2.3); leaders also manage the rest of a member in their dialog.
    val canShare = can.setMemberShare()
    // Everyone else gets a smaller dialog: the default share for leads, settling together (ITERATION_5 §7) for all.
    val me = state.me
    val open: (Member) -> (() -> Unit)? = { member ->
        val pair = me != null && hasSettleTogether(band, me, member)
        if (isAdmin || (!member.archived && (canShare || pair))) ({ roster.open(member, state.userId, roster.invites != null) }) else null
    }

    AppScaffold(
        messages,
        topBar = { BackTopBar(stringResource(Res.string.menu_roster), nav::back) },
        floatingActionButton = {
            if (isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = roster::add,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(Res.string.add_member)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).listWidth()) {
            if (isAdmin && roster.invitesFailed) {
                item { Text(stringResource(Res.string.invites_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (searchable) item(key = "search") { NameSearchField(query, { roster.query = it }, Modifier.padding(16.dp, 8.dp)) }
            for (group in RoleGroup.entries) {
                val members = active.filter { group.has(it) }
                if (members.isEmpty()) continue
                item(key = "group-${group.name}") { GroupTitle(stringResource(group.title, members.size)) }
                items(members, key = { it.id }) { member ->
                    MemberRow(
                        member,
                        roster.inviteOf(member.id),
                        band.currency,
                        onClick = open(member),
                        isMyPartner = me != null && member.id == me.settlesWith,
                        namesOnly = namesOnly,
                    )
                }
            }
            if (searchable && query.isNotBlank() && active.isEmpty() && (archived.isEmpty() || namesOnly)) {
                item(key = "no-match") { Text(stringResource(Res.string.search_people_none, query.trim()), Modifier.padding(16.dp)) }
            }
            // Not for artists: they only need those they could settle together with (ITERATION_6 §11).
            if (archived.isNotEmpty() && !namesOnly) {
                // Collapsed at first: only a count, so a long list of past players doesn't bury the band.
                item(key = "archived-title") {
                    ListItem(
                        modifier = Modifier.clickable { roster.showArchived = !roster.showArchived },
                        headlineContent = { Text(stringResource(Res.string.roster_archived, archived.size), style = MaterialTheme.typography.titleMedium) },
                        trailingContent = { Icon(if (roster.showArchived) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                    )
                }
                // A search reaching former members opens them (ITERATION_8 §2).
                if (roster.showArchived || (searchable && query.isNotBlank())) {
                    items(archived, key = { it.id }) { member ->
                        MemberRow(member, roster.inviteOf(member.id), band.currency, onClick = open(member), namesOnly = namesOnly)
                    }
                }
            }
        }
    }

    when (val dialog = roster.dialog) {
        RosterDialog.Adding -> TextInputDialog(
            stringResource(Res.string.add_member),
            stringResource(Res.string.member_name),
            "",
            onConfirm = { actions.run(AddMember(newId(), it)) },
            onDismiss = roster::dismiss,
            // Someone who used to be in the band is offered back instead (their name stays theirs).
            error = { name -> nameTakenError(band, name).takeIf { band.formerMemberNamed(name) == null } },
            allowed = { band.formerMemberNamed(it) == null },
            below = { name -> band.formerMemberNamed(name)?.let { FormerMember(it, actions, onRestored = roster::dismiss) } },
        )

        is RosterDialog.OfMember -> {
            val member = band.member(dialog.form.memberId)
            if (member != null && isAdmin) {
                MemberDialog(
                    dialog.form,
                    member,
                    band,
                    state.view,
                    me,
                    can,
                    actions,
                    invitesFailed = roster.invitesFailed,
                    onEditShare = { roster.share(true) }.takeIf { canShare && !member.archived },
                    onDismiss = roster::dismiss,
                )
            } else if (member != null && !member.archived) {
                SmallMemberDialog(member, band, me, canShare, actions, onEditShare = { roster.share(true) }, onDismiss = roster::dismiss)
            }
            if (member != null && dialog.sharing) MemberShareDialog(band, member, actions, onDismiss = { roster.share(false) })
        }

        null -> Unit
    }
}

/** A member for someone who does not manage the band: the default share for leads, settling together for all. */
@Composable
private fun SmallMemberDialog(
    member: Member,
    band: Band,
    me: Member?,
    canShare: Boolean,
    actions: Actions,
    onEditShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canShare) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shareLine(member, band.currency), Modifier.weight(1f))
                        EditButton(onClick = onEditShare)
                    }
                }
                if (me != null && hasSettleTogether(band, me, member)) SettleTogetherSection(band, me, member, actions)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** "1½ shares", "20% of profit" or "300 zł (fixed)": a member's default share (ITERATION_4 §2.3); null for a normal one. */
@Composable
private fun shareText(member: Member, currency: String): String? = shareText(member.shareWeight, member.shareFixed, member.sharePercent, currency)

/** "Share: 1 share" in a member's dialog, where the share can be edited: said even when normal. */
@Composable
private fun shareLine(member: Member, currency: String): String = stringResource(Res.string.member_share_line, shareText(member, currency) ?: sharesText(member.shareWeight))

/**
 * A member's default share (ITERATION_4 §2.3). Saving it offers to use it in their events not settled yet where they
 * still have the old one; settled and archived events are never changed.
 */
@Composable
private fun MemberShareDialog(band: Band, member: Member, actions: Actions, onDismiss: () -> Unit) {
    val editor = rememberShareEditorState(member.shareWeight, member.shareFixed, member.sharePercent)
    // The new default as a participant has it, while asking whether to use it in events too.
    var applying by remember { mutableStateOf<Participant?>(null) }
    // Not on every slider step: it settles each event.
    val stillOld = remember(band, member) { MemberShares.stillDefault(BandView(band), member) }
    fun save(share: Participant, events: List<Event>) {
        if (actions.run(SetMemberShare(member.id, share.weight, share.fixedShare, events.map { it.id }, share.percent))) onDismiss()
    }
    val pending = applying
    if (pending == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("${stringResource(Res.string.member_share_title)}: ${member.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareEditor(editor, band.currency, band.fundDefault?.percent ?: 0)
                    Text(stringResource(Res.string.member_share_hint), style = MaterialTheme.typography.bodySmall)
                    DialogNotice(actions)
                }
            },
            confirmButton = {
                TextButton(enabled = editor.valid, onClick = {
                    // A fixed share or a percentage keeps the weight it had, so switching back restores it (as in events).
                    val share = editor.applyTo(member.asParticipant())
                    when {
                        share == member.asParticipant() -> onDismiss()
                        stillOld.isEmpty() -> save(share, emptyList())
                        else -> applying = share
                    }
                }) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        )
    } else {
        val names = stillOld.take(3).joinToString(", ") { it.name } + if (stillOld.size > 3) "…" else ""
        AlertDialog(
            // Save was pressed already: leaving the question keeps the new default, without touching events.
            onDismissRequest = { save(pending, emptyList()) },
            title = { Text(stringResource(Res.string.member_share_apply_title)) },
            text = { Text(stringResource(Res.string.member_share_apply_text, member.name, stillOld.size, names)) },
            confirmButton = { TextButton(onClick = { save(pending, stillOld) }) { Text(stringResource(Res.string.member_share_apply_yes)) } },
            dismissButton = { TextButton(onClick = { save(pending, emptyList()) }) { Text(stringResource(Res.string.member_share_apply_no)) } },
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

/** A roster row: the group says the role; only the owner's row says Owner. */
@Composable
private fun MemberRow(
    member: Member,
    invite: InviteInfo?,
    currency: String,
    onClick: (() -> Unit)?,
    /** You settle together with them (ITERATION_5 §7): only your own partner is marked, nobody else's pair. */
    isMyPartner: Boolean = false,
    namesOnly: Boolean = false,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        // Decorative: the name follows right after it (ITERATION_3 R13).
        // Without an app account: can't see the band (ITERATION_5 §12).
        leadingContent = { MemberAvatar(member, 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.name, color = if (member.archived) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                if (isMyPartner) {
                    Icon(
                        Icons.Default.Group,
                        stringResource(Res.string.settle_together_title),
                        Modifier.padding(start = 6.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        supportingContent = if (namesOnly) {
            null
        } else {
            (
                {
                    Column {
                        if (member.userId != null && member.role == Role.OWNER) Text(member.role.label())
                        // Former members have no group of their own role.
                        if (member.archived) Text(if (member.userId != null) member.role.label() else stringResource(Res.string.not_linked))
                        if (invite != null) Text(stringResource(Res.string.invite_active_until, invite.code, expiryText(invite)))
                        // A normal share goes unsaid: it is everyone's, until changed.
                        shareText(member, currency)?.let { Text(it) }
                    }
                }
                )
        },
    )
    HorizontalDivider()
}

@Composable
private fun expiryText(invite: InviteInfo): String = formatDate(Instant.fromEpochMilliseconds(invite.expiresAt).toLocalDateTime(TimeZone.currentSystemDefault()).date, language)

/** A leader's dialog for one member ([MemberForm]): name, share, role, settling together, invite, and the rest. */
@Composable
private fun MemberDialog(
    form: MemberForm,
    member: Member,
    band: Band,
    view: BandView,
    me: Member?,
    can: Capabilities,
    actions: Actions,
    invitesFailed: Boolean,
    /** Opens the default share (ITERATION_4 §2.3); null for those who may not change it. */
    onEditShare: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val isOwner = member.role == Role.OWNER
    val isMe = form.isMe(member)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val taken = nameTakenError(band, form.name, except = member.id)
                    OutlinedTextField(
                        form.name,
                        { form.name = it },
                        label = { Text(stringResource(Res.string.member_name)) },
                        singleLine = true,
                        isError = taken != null,
                        supportingText = taken?.let { { Text(it) } },
                        modifier = Modifier.weight(1f),
                    )
                    val rename = form.rename(member, taken != null)
                    TextButton(enabled = rename != null, onClick = { rename?.let { actions.run(it) } }) {
                        Text(stringResource(Res.string.save))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shareLine(member, band.currency), Modifier.weight(1f))
                    if (onEditShare != null) EditButton(onClick = onEditShare)
                }
                // Without an account the role only takes effect when they join, which the invite's role decides. A linked
                // member's role is changed rarely: one row, the choices behind it.
                if (!isOwner && member.userId != null) {
                    val pickRole = { form.ask(MemberQuestion.PickingRole) }
                    Row(Modifier.fillMaxWidth().clickable(onClick = pickRole), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.role_row, member.role.label()), Modifier.weight(1f))
                        EditButton(stringResource(Res.string.role), onClick = pickRole)
                    }
                }
                if (me != null && hasSettleTogether(band, me, member)) SettleTogetherSection(band, me, member, actions)
                if (member.userId == null && !member.archived) {
                    HorizontalDivider()
                    InviteSection(form, band, view, invitesFailed, actions)
                }
                MemberActions(form, member, band, can, actions)
                DialogNotice(actions)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
    MemberQuestions(form, member, band, actions, onDismiss)
}

/** What a leader may do to a member besides editing: unlink their account, make them the owner, archive, delete. */
@Composable
private fun MemberActions(form: MemberForm, member: Member, band: Band, can: Capabilities, actions: Actions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val isOwner = member.role == Role.OWNER
        val isMe = form.isMe(member)
        // Unlinking your own account would lock you out of the band.
        if (member.userId != null && !isOwner && !isMe) {
            OutlinedButton(onClick = { form.ask(MemberQuestion.ConfirmUnlink) }) { Text(stringResource(Res.string.unlink)) }
        }
        if (can.transferOwnership() && member.userId != null && member.role == Role.LEAD) {
            OutlinedButton(onClick = { form.ask(MemberQuestion.ConfirmOwner) }) { Text(stringResource(Res.string.make_owner)) }
        }
        // Not yourself: an archived member can do nothing in the band, so you could not undo it (V5-M1 C5).
        if (!isOwner && !isMe) {
            OutlinedButton(onClick = { form.toggleArchived(member)?.let { actions.run(it) } }) {
                Text(stringResource(if (member.archived) Res.string.unarchive else Res.string.archive))
            }
        }
        // Only who left no trace (ITERATION_7 §3.1); anyone else is moved to the former members above.
        if (!isOwner && !isMe && can.allowed(DeleteMember(member.id)) && MemberDeletion.possible(band, member.id)) {
            TextButton(
                onClick = { form.ask(MemberQuestion.ConfirmDelete) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.delete_member)) }
        }
    }
}

/** The invite of a member without an account: the code shown with its ways to pass it on, or the role and Create. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteSection(form: MemberForm, band: Band, view: BandView, invitesFailed: Boolean, actions: Actions) {
    val scope = rememberCoroutineScope()
    val share = rememberShare()
    val copy = rememberCopy()
    // In the app's language (a non-composable getString would use the browser's on the web).
    val failedText = stringResource(Res.string.invite_failed)
    val holdsMoneyText = stringResource(Res.string.reject_holds_money)
    val stillPlaysText = stringResource(Res.string.reject_viewer_still_plays)
    fun tell(outcome: InviteOutcome?) {
        when (outcome) {
            null -> Unit

            InviteOutcome.Failed -> actions.say(failedText)

            is InviteOutcome.Refused -> actions.say(
                when (outcome.reason) {
                    RejectionReason.HOLDS_MONEY -> holdsMoneyText
                    RejectionReason.VIEWER_STILL_PLAYS -> stillPlaysText
                    else -> failedText
                },
            )
        }
    }
    val current = form.shownInvite
    if (current != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
                val until = stringResource(Res.string.invite_active_until, current.code, expiryText(current))
                Text("${current.role.label()} · $until", style = MaterialTheme.typography.bodySmall)
                val link = "${AppConfig.WEB_URL}/join/${current.code}"
                val text = stringResource(Res.string.share_invite_text, band.name, current.code, link)
                val copied = stringResource(Res.string.invite_copied)
                val linkCopied = stringResource(Res.string.link_copied)
                // A QR code to scan from another phone; the link itself goes by Share or Copy link.
                QrCode(link, stringResource(Res.string.invite_qr), Modifier.align(Alignment.CenterHorizontally))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch { if (share(text) == ShareResult.COPIED) actions.say(copied) }
                    }) { Text(stringResource(Res.string.share_invite)) }
                    OutlinedButton(onClick = {
                        scope.launch { actions.say(if (copy(link)) linkCopied else failedText) }
                    }) { Text(stringResource(Res.string.copy_link)) }
                    OutlinedButton(enabled = !form.inviteBusy, onClick = { scope.launch { tell(form.revoke()) } }) {
                        Text(stringResource(Res.string.revoke_invite))
                    }
                }
            }
        }
    } else if (invitesFailed) {
        Text(stringResource(Res.string.invites_unavailable), color = MaterialTheme.colorScheme.error)
    } else if (!form.inviteKnown) {
        CircularProgressIndicator()
    } else {
        Text(stringResource(Res.string.invite_hint), style = MaterialTheme.typography.bodySmall)
        RolePicker(form.inviteRole, { form.inviteRole = it }, stringResource(Res.string.invite_role))
        FilledTonalButton(enabled = !form.inviteBusy, onClick = { scope.launch { tell(form.create(view)) } }) {
            Text(stringResource(Res.string.create_invite))
        }
    }
}

/** The question the member's dialog asks before acting, if any. */
@Composable
private fun MemberQuestions(form: MemberForm, member: Member, band: Band, actions: Actions, onDismiss: () -> Unit) {
    when (val question = form.dialog) {
        MemberQuestion.ConfirmDelete -> {
            val deleted = stringResource(Res.string.deleted_member, member.name)
            ConfirmDialog(
                title = stringResource(Res.string.delete_member_title, member.name),
                text = listOfNotNull(
                    stringResource(Res.string.delete_member_text, member.name),
                    stringResource(Res.string.delete_member_linked, member.name).takeIf { member.userId != null },
                ).joinToString("\n\n"),
                confirmLabel = stringResource(Res.string.delete),
                onConfirm = {
                    // The account first, for good (not undoable); Undo then brings the member back without it (ITERATION_7 §3.1).
                    // The dialog closes, so Undo doesn't bring it back with the member.
                    val (unlink, delete) = form.deletion(member)
                    if ((unlink == null || actions.run(unlink)) && actions.runWithUndo(delete, deleted)) onDismiss()
                },
                onDismiss = form::dismissDialog,
            )
        }

        MemberQuestion.ConfirmUnlink -> ConfirmDialog(
            title = stringResource(Res.string.unlink),
            text = listOfNotNull(stringResource(Res.string.unlink_confirm, member.name), pairEndsWarning(band, member)).joinToString("\n\n"),
            confirmLabel = stringResource(Res.string.unlink),
            onConfirm = { actions.run(form.unlink(member)) },
            onDismiss = form::dismissDialog,
        )

        MemberQuestion.PickingRole -> AlertDialog(
            onDismissRequest = form::dismissDialog,
            title = { Text(member.name) },
            text = {
                val picked: (Role) -> Unit = { role -> form.pickRole(member, role)?.let { actions.run(it) } }
                RolePicker(member.role, picked, stringResource(Res.string.role), Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = form::dismissDialog) { Text(stringResource(Res.string.cancel)) } },
        )

        is MemberQuestion.ConfirmRole -> {
            val role = question.role
            val warning = pairEndsWarning(band, member, role)
            val ownText = listOfNotNull(stringResource(Res.string.change_own_role_text, role.label()), warning).joinToString("\n\n")
            val ownTitle = stringResource(Res.string.change_own_role_title)
            ConfirmDialog(
                title = if (question.own) ownTitle else stringResource(Res.string.role_row, role.label()),
                text = if (question.own) ownText else warning,
                confirmLabel = stringResource(if (question.own) Res.string.change_own_role_confirm else Res.string.save),
                onConfirm = { form.confirmRole(member)?.let { actions.run(it) } },
                onDismiss = form::dismissDialog,
            )
        }

        MemberQuestion.ConfirmArchive -> ConfirmDialog(
            title = stringResource(Res.string.archive),
            text = pairEndsWarning(band, member),
            confirmLabel = stringResource(Res.string.archive),
            onConfirm = { actions.run(form.confirmArchive(member)) },
            onDismiss = form::dismissDialog,
        )

        MemberQuestion.ConfirmOwner -> ConfirmDialog(
            title = stringResource(Res.string.make_owner),
            text = stringResource(Res.string.make_owner_confirm, member.name),
            confirmLabel = stringResource(Res.string.make_owner),
            onConfirm = { if (actions.run(form.transferOwnership(member))) onDismiss() },
            onDismiss = form::dismissDialog,
        )

        null -> Unit
    }
}
