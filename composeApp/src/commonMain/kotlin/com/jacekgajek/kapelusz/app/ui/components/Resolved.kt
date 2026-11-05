package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Closes a dialog whose subject has disappeared.
 *
 * A dialog opens on something the band had at the time, e.g. a cost or a collected entry. A sync while it is open
 * may remove that thing. The dialog looks it up again on each recomposition and passes the result as [current]:
 * when it is null, [onDismiss] is called once and the dialog must draw nothing; otherwise it is returned unchanged.
 *
 * Usage: `val cost = resolved(band.cost(id), onDismiss) ?: return`
 */
@Composable
fun <T : Any> resolved(current: T?, onDismiss: () -> Unit): T? {
    val dismiss by rememberUpdatedState(onDismiss)
    if (current == null) LaunchedEffect(Unit) { dismiss() }
    return current
}
