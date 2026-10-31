package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.edit
import com.jacekgajek.kapelusz.app.generated.remove
import org.jetbrains.compose.resources.stringResource

/** Visible edit affordance at the end of a tappable row (touch screens show no hover highlight). */
@Composable
fun EditButton(what: String? = null, onClick: () -> Unit) {
    val edit = stringResource(Res.string.edit)
    // Named after the item where there are several in a list, as RemoveButton is: "Edit: Anna".
    IconButton(onClick = onClick) { Icon(Icons.Default.Edit, if (what.isNullOrBlank()) edit else "$edit: $what") }
}

/** Visible remove affordance at the end of a tappable row; [what] names the item for screen readers (ITERATION_3 R13). */
@Composable
fun RemoveButton(what: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.Delete, removeLabel(what)) }
}

/** "Remove: Anna", or just "Remove" for an item without a name yet (e.g. an empty field). */
@Composable
fun removeLabel(what: String): String {
    val remove = stringResource(Res.string.remove)
    return if (what.isBlank()) remove else "$remove: $what"
}
