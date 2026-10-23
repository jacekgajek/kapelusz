package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.role_desc_admin
import com.jacekgajek.kapelusz.app.generated.role_desc_artist
import com.jacekgajek.kapelusz.app.generated.role_desc_lead
import com.jacekgajek.kapelusz.app.generated.role_desc_member
import com.jacekgajek.kapelusz.app.generated.role_desc_viewer
import com.jacekgajek.kapelusz.app.generated.role_owner_note
import com.jacekgajek.kapelusz.model.Role
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Roles an admin can give; ownership only changes by transfer. */
val ASSIGNABLE_ROLES = listOf(Role.VIEWER, Role.ARTIST, Role.MEMBER, Role.LEAD, Role.ADMIN)

private fun Role.description(): StringResource = when (this) {
    Role.VIEWER -> Res.string.role_desc_viewer
    Role.ARTIST -> Res.string.role_desc_artist
    Role.MEMBER -> Res.string.role_desc_member
    Role.LEAD -> Res.string.role_desc_lead
    Role.ADMIN, Role.OWNER -> Res.string.role_desc_admin
}

/** Radio buttons for a role, each with what it allows (§3 of the plan), and a note on ownership. */
@Composable
fun RolePicker(selected: Role, onSelect: (Role) -> Unit, title: String, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        ASSIGNABLE_ROLES.forEach { role ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = role == selected, onClick = { onSelect(role) }, role = SemanticsRole.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = role == selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(role.label(), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(role.description()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(Res.string.role_owner_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
