package com.jacekgajek.artsplit.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.back
import com.jacekgajek.artsplit.app.generated.menu_roster
import com.jacekgajek.artsplit.app.generated.menu_settings
import com.jacekgajek.artsplit.app.generated.settings
import org.jetbrains.compose.resources.stringResource

/** Top bar of a tab screen, with the cog menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, onSettings: () -> Unit, onRoster: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.settings))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.menu_roster)) }, onClick = { menuOpen = false; onRoster() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.menu_settings)) }, onClick = { menuOpen = false; onSettings() })
            }
        },
    )
}

/** Top bar of a pushed screen, with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        },
        actions = actions,
    )
}
