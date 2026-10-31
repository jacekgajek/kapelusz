package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.former_member
import com.jacekgajek.kapelusz.app.generated.former_member_restore
import com.jacekgajek.kapelusz.app.generated.former_member_restored
import com.jacekgajek.kapelusz.app.generated.name_taken
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.view.initials
import org.jetbrains.compose.resources.stringResource

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
 * In place of the avatar of a member without an app account, who can't see the band. Described for
 * screen readers when [description] is given (when it can be tapped).
 */
@Composable
fun NotInAppIcon(size: Dp = 28.dp, description: String? = null) = AvatarIcon(Icons.Default.VisibilityOff, size, description)

/** In place of a guest's avatar: someone playing this one event with the band. */
@Composable
fun GuestIcon(size: Dp = 28.dp) = AvatarIcon(Icons.Default.Handshake, size, null)

/** A member's avatar beside their name, or the crossed eye when they have no app account. */
@Composable
fun MemberAvatar(member: Member, size: Dp = 28.dp) {
    if (member.userId == null) NotInAppIcon(size) else Avatar(member.name, member.pictureUrl, size, decorative = true)
}

/** A party's avatar beside its name in [event]: the handshake for a guest, else the member's ([MemberAvatar]). */
@Composable
fun PartyAvatar(band: Band, event: Event, partyId: String, size: Dp = 28.dp) {
    val member = band.member(partyId)
    when {
        event.guest(partyId) != null -> GuestIcon(size)
        member == null -> NotInAppIcon(size)
        else -> MemberAvatar(member, size)
    }
}

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

/** A member's name is free: null, or why not (a member already has it). */
@Composable
fun nameTakenError(band: Band, name: String, except: String? = null): String? = band.memberNamed(name, except)?.takeIf { name.isNotBlank() }?.let { stringResource(Res.string.name_taken, it.name) }
