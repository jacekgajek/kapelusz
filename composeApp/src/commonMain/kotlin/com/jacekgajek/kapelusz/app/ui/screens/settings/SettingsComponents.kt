package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.MAX_LIST_WIDTH
import com.jacekgajek.kapelusz.app.ui.components.Messages
import com.jacekgajek.kapelusz.app.ui.text.label

/** A settings page: the top bar, the screen's messages, and a centred column that does not stretch on wide screens. */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    messages: Messages,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppScaffold(messages, topBar = { BackTopBar(title, onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            val inset = if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 8.dp)
            Column(Modifier.widthIn(max = MAX_LIST_WIDTH).fillMaxWidth().then(inset), content = content)
        }
    }
}

/** One entry of the settings list: icon, name, current value; tapping opens it (a page when [chevron]). */
@Composable
internal fun SettingsItem(icon: ImageVector, title: String, summary: String?, chevron: Boolean = false, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = if (chevron) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) else null,
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    )
}

/** A heading within a settings page. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
