package com.jacekgajek.kapelusz.app.ui.components

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.roster_group_artists
import com.jacekgajek.kapelusz.app.generated.roster_group_leaders
import com.jacekgajek.kapelusz.app.generated.roster_group_members
import com.jacekgajek.kapelusz.app.generated.roster_group_not_in_app
import com.jacekgajek.kapelusz.app.generated.roster_group_viewers
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import org.jetbrains.compose.resources.StringResource

/**
 * Members by role, in this order, on the members screen and in the line-up picker: by role while
 * linked to an account; those without one apart, whatever role is stored (it applies only once they join).
 * [title] takes the count.
 */
enum class RoleGroup(val title: StringResource, val has: (Member) -> Boolean) {
    LEADERS(Res.string.roster_group_leaders, { it.userId != null && it.role.atLeast(Role.LEAD) }),
    MEMBERS(Res.string.roster_group_members, { it.userId != null && it.role == Role.MEMBER }),
    ARTISTS(Res.string.roster_group_artists, { it.userId != null && it.role == Role.ARTIST }),
    VIEWERS(Res.string.roster_group_viewers, { it.userId != null && it.role == Role.VIEWER }),
    NOT_IN_APP(Res.string.roster_group_not_in_app, { it.userId == null }),
}
