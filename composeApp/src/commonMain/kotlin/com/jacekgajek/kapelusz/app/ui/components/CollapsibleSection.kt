package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.section_collapse
import com.jacekgajek.kapelusz.app.generated.section_collapsed
import com.jacekgajek.kapelusz.app.generated.section_expand
import com.jacekgajek.kapelusz.app.generated.section_expanded
import org.jetbrains.compose.resources.stringResource

/** Whether a collapsible section is open, and how to switch it (ITERATION_4 §2.4). */
class SectionState(val expanded: Boolean, val toggle: () -> Unit) {
    companion object {
        /** Always open: for a screen without collapsing. */
        val Open = SectionState(true) {}
    }
}

/**
 * The title row of a collapsible section: tapping it opens or closes the section. While closed, [summary] says the gist
 * next to the title (e.g. a total); [actions] (e.g. an edit button) sit before the chevron.
 */
@Composable
fun CollapsibleHeader(title: String, state: SectionState, summary: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val stateText = stringResource(if (state.expanded) Res.string.section_expanded else Res.string.section_collapsed)
    val action = stringResource(if (state.expanded) Res.string.section_collapse else Res.string.section_expand)
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = action, onClick = state.toggle)
            .semantics { stateDescription = stateText }
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lets screen reader users jump between the sections, as SectionTitle does.
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (!state.expanded && summary != null) summary()
        }
        if (state.expanded) actions()
        Icon(if (state.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}
