package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.add_member
import com.jacekgajek.artsplit.app.generated.archive
import com.jacekgajek.artsplit.app.generated.archived_member
import com.jacekgajek.artsplit.app.generated.member_name
import com.jacekgajek.artsplit.app.generated.menu_roster
import com.jacekgajek.artsplit.app.generated.move_down
import com.jacekgajek.artsplit.app.generated.move_up
import com.jacekgajek.artsplit.app.generated.not_linked
import com.jacekgajek.artsplit.app.generated.rename
import com.jacekgajek.artsplit.app.generated.unarchive
import com.jacekgajek.artsplit.app.ui.components.Avatar
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.TextInputDialog
import com.jacekgajek.artsplit.app.ui.components.label
import com.jacekgajek.artsplit.app.ui.components.rememberActions
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.MoveMember
import com.jacekgajek.artsplit.ops.RenameMember
import com.jacekgajek.artsplit.ops.SetMemberArchived
import org.jetbrains.compose.resources.stringResource

/** Admin: the band roster. The order is used for tie-breaking in settlements. */
@Composable
fun RosterScreen(graph: AppGraph, state: BandState.Ready, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Member?>(null) }
    val members = state.band.members

    Scaffold(
        topBar = { BackTopBar(stringResource(Res.string.menu_roster), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.add_member)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(members, key = { _, member -> member.id }) { index, member ->
                ListItem(
                    leadingContent = { Avatar(member.name, 40.dp) },
                    headlineContent = {
                        Text(member.name, color = if (member.archived) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                    },
                    supportingContent = {
                        Column {
                            Text(if (member.userId != null) member.role.label() else stringResource(Res.string.not_linked))
                            if (member.archived) Text(stringResource(Res.string.archived_member))
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { actions.run(MoveMember(member.id, index - 1)) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.move_up))
                            }
                            IconButton(onClick = { actions.run(MoveMember(member.id, index + 1)) }, enabled = index < members.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.move_down))
                            }
                            IconButton(onClick = { renaming = member }) { Icon(Icons.Default.Edit, stringResource(Res.string.rename)) }
                            IconButton(onClick = { actions.run(SetMemberArchived(member.id, !member.archived)) }) {
                                if (member.archived) {
                                    Icon(Icons.Default.Unarchive, stringResource(Res.string.unarchive))
                                } else {
                                    Icon(Icons.Default.Archive, stringResource(Res.string.archive))
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
    renaming?.let { member ->
        TextInputDialog(
            stringResource(Res.string.rename),
            stringResource(Res.string.member_name),
            member.name,
            onConfirm = { actions.run(RenameMember(member.id, it)) },
            onDismiss = { renaming = null },
        )
    }
}
