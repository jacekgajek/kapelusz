package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** A screen of the band: its top bar, the band's messages as snackbars, and a floating action button if it has one. */
@Composable
fun AppScaffold(
    messages: Messages,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(topBar = topBar, snackbarHost = { MessageHost(messages) }, floatingActionButton = floatingActionButton, content = content)
}

/** What a screen shows instead of its content: the thing is gone, or there is nothing yet. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
