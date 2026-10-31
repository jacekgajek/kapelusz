package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Returns [current], what a dialog was opened for as the band has it now; null when a sync took it away meanwhile, and then the dialog
 * closes itself ([onDismiss], once) instead of showing something stale. At the call
 * site: `val cost = resolved(band.cost(id), onDismiss) ?: return`.
 */
@Composable
fun <T : Any> resolved(current: T?, onDismiss: () -> Unit): T? {
    val dismiss by rememberUpdatedState(onDismiss)
    if (current == null) LaunchedEffect(Unit) { dismiss() }
    return current
}
