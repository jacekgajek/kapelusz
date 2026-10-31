package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.app.ui.components.Crowd
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.rules.RoleChanges
import com.jacekgajek.kapelusz.text.Names
import com.jacekgajek.kapelusz.text.sortedByName
import com.jacekgajek.kapelusz.view.BandView
import kotlinx.coroutines.launch

/** What is open over the band members list. */
sealed interface RosterDialog {
    /** Adding a member by name. */
    data object Adding : RosterDialog

    /** A member's dialog ([form]); [sharing] when their default share is being edited on top of it. */
    data class OfMember(val form: MemberForm, val sharing: Boolean = false) : RosterDialog
}

/** The members list, grouped as the screen shows it: active ones (by role in the screen), former ones below. */
data class RosterLists(val active: List<Member>, val archived: List<Member>, val searchable: Boolean)

/**
 * The band members screen (ITERATION_8 §2): the invites for leaders, the search, the former members' fold, and the
 * dialog open on top. Scoped to the screen's back-stack entry.
 */
class RosterViewModel(
    private val invitesOf: suspend () -> List<InviteInfo>,
    private val createInvite: suspend (memberId: String, Role) -> InviteInfo,
    private val revokeInvite: suspend (code: String) -> Unit,
) : ViewModel() {
    /** The server's open invites, for leaders; null until loaded. */
    var invites by mutableStateOf<List<InviteInfo>?>(null)
        private set
    var invitesFailed by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
    var showArchived by mutableStateOf(false)
    var dialog by mutableStateOf<RosterDialog?>(null)
        private set
    private var routeMemberOpened = false

    fun reloadInvites() {
        viewModelScope.launch {
            runSuspendCatching { invitesOf() }
                .onSuccess {
                    invites = it
                    invitesFailed = false
                }
                .onFailure { invitesFailed = true }
        }
    }

    fun inviteOf(memberId: String): InviteInfo? = invites?.firstOrNull { it.memberId == memberId }

    /**
     * By role, alphabetical within; archived members in a section of their own: bands that change players collect
     * many of them. A search (offered for a crowd) narrows both, so a former member is found too.
     */
    fun lists(band: Band): RosterLists {
        val (allArchived, allActive) = band.members.sortedByName { it.name }.partition { it.archived }
        val searchable = allActive.size > Crowd.THRESHOLD
        return if (searchable && query.isNotBlank()) {
            val matching = { member: Member -> Names.matches(member.name, query) }
            RosterLists(allActive.filter(matching), allArchived.filter(matching), searchable)
        } else {
            RosterLists(allActive, allArchived, searchable)
        }
    }

    fun add() {
        dialog = RosterDialog.Adding
    }

    /** Opens [member]'s dialog; a leader's with everything, anyone else's with the share and settling together. */
    fun open(member: Member, myUserId: String, inviteKnown: Boolean) {
        val form = MemberForm(member.id, member, myUserId, inviteOf(member.id), inviteKnown, createInvite, revokeInvite, ::reloadInvites)
        dialog = RosterDialog.OfMember(form)
    }

    /**
     * Opened at a member from elsewhere (an event's "invite them", ITERATION_5 §12): once they are in the band, and
     * not again after a rotation.
     */
    fun openFromRoute(member: Member?, myUserId: String) {
        if (routeMemberOpened || member == null) return
        routeMemberOpened = true
        open(member, myUserId, invites != null)
    }

    /** The default share editor on top of the member's dialog (ITERATION_4 §2.3). */
    fun share(on: Boolean) {
        val open = dialog as? RosterDialog.OfMember ?: return
        dialog = open.copy(sharing = on)
    }

    fun dismiss() {
        dialog = null
    }

    /** The invites list arrived: the open member's dialog may now show theirs. */
    fun invitesLoaded() {
        (dialog as? RosterDialog.OfMember)?.form?.invitesLoaded(invites)
    }
}

/** A question asked in a member's dialog before acting. */
sealed interface MemberQuestion {
    data object PickingRole : MemberQuestion
    data class ConfirmRole(val role: Role, val own: Boolean) : MemberQuestion
    data object ConfirmUnlink : MemberQuestion
    data object ConfirmOwner : MemberQuestion
    data object ConfirmArchive : MemberQuestion
    data object ConfirmDelete : MemberQuestion
}

/** What creating an invite was refused for before asking the server, which refuses for the same reasons. */
sealed interface InviteOutcome {
    data class Refused(val reason: RejectionReason) : InviteOutcome
    data object Failed : InviteOutcome
}

/**
 * One member's dialog for a leader: the name being edited, the invite shown and its role, the question open, and
 * what each button does (returning the operations the screen runs, so refusals show in the dialog).
 */
@Stable
class MemberForm(
    val memberId: String,
    opened: Member,
    private val myUserId: String,
    invite: InviteInfo?,
    inviteKnown: Boolean,
    private val createInvite: suspend (memberId: String, Role) -> InviteInfo,
    private val revokeInvite: suspend (code: String) -> Unit,
    private val onInvitesChanged: () -> Unit,
) {
    var name by mutableStateOf(opened.name)
    var inviteRole by mutableStateOf(if (opened.role == Role.OWNER) Role.MEMBER else opened.role)
    var dialog by mutableStateOf<MemberQuestion?>(null)
        private set

    /** The invite shown; an invite created before the list has loaded would silently replace a shared code. */
    var shownInvite by mutableStateOf(invite)
        private set
    var inviteKnown by mutableStateOf(inviteKnown)
        private set
    var inviteBusy by mutableStateOf(false)
        private set

    fun invitesLoaded(invites: List<InviteInfo>?) {
        if (invites != null && !inviteKnown) {
            shownInvite = invites.firstOrNull { it.memberId == memberId }
            inviteKnown = true
        }
    }

    fun isMe(member: Member) = member.userId == myUserId

    /** Save beside the name: a new, free name. */
    fun rename(member: Member, taken: Boolean): Operation? {
        val changed = name.isNotBlank() && name.trim() != member.name
        return RenameMember(member.id, name).takeIf { changed && !taken }
    }

    fun ask(question: MemberQuestion) {
        dialog = question
    }

    fun dismissDialog() {
        dialog = null
    }

    /**
     * A role picked: changing your own can take away the rights you are using now, and a change that ends settling
     * together (ITERATION_5 §7) is said first; otherwise it applies at once.
     */
    fun pickRole(member: Member, role: Role): Operation? {
        dialog = null
        return when {
            role == member.role -> null

            isMe(member) -> {
                dialog = MemberQuestion.ConfirmRole(role, own = true)
                null
            }

            pairEndsWarningNeeded(member, role) -> {
                dialog = MemberQuestion.ConfirmRole(role, own = false)
                null
            }

            else -> SetMemberRole(member.id, role)
        }
    }

    fun confirmRole(member: Member): Operation? {
        val role = (dialog as? MemberQuestion.ConfirmRole)?.role ?: return null
        dialog = null
        return SetMemberRole(member.id, role)
    }

    /** Archive or bring back; archiving ends their settling together: said first. */
    fun toggleArchived(member: Member): Operation? {
        if (!member.archived && member.settlesWith != null) {
            dialog = MemberQuestion.ConfirmArchive
            return null
        }
        return SetMemberArchived(member.id, !member.archived)
    }

    fun confirmArchive(member: Member): Operation {
        dialog = null
        return SetMemberArchived(member.id, true)
    }

    fun unlink(member: Member): Operation {
        dialog = null
        return UnlinkUser(member.id)
    }

    fun transferOwnership(member: Member): Operation {
        dialog = null
        return TransferOwnership(member.id)
    }

    /** Deleting: the account first, for good (not undoable); Undo then brings the member back without it (ITERATION_7 §3.1). */
    fun deletion(member: Member): Pair<Operation?, Operation> {
        dialog = null
        return (UnlinkUser(member.id).takeIf { member.userId != null }) to DeleteMember(member.id)
    }

    /** Asks the server for an invite, told first what the band would refuse; one at a time (two taps: two codes). */
    suspend fun create(view: BandView): InviteOutcome? {
        RoleChanges.refusal(view, memberId, inviteRole)?.let { return InviteOutcome.Refused(it) }
        return inviteCall { shownInvite = createInvite(memberId, inviteRole) }
    }

    suspend fun revoke(): InviteOutcome? {
        val current = shownInvite ?: return null
        return inviteCall {
            revokeInvite(current.code)
            shownInvite = null
        }
    }

    private suspend fun inviteCall(block: suspend () -> Unit): InviteOutcome? {
        if (inviteBusy) return null
        inviteBusy = true
        try {
            val result = runSuspendCatching { block() }
            if (result.isFailure) return InviteOutcome.Failed
            onInvitesChanged()
            return null
        } finally {
            inviteBusy = false
        }
    }
}
