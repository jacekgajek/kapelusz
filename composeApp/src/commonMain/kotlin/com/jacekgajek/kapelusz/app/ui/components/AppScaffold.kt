package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

/** The widest a list grows: on tablets it stays readable, centred (ITERATION_3 R13). */
val MAX_LIST_WIDTH = 600.dp

/** Caps a list (after its size modifiers) at [MAX_LIST_WIDTH], centred in the space it was given. */
fun Modifier.listWidth(): Modifier = wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = MAX_LIST_WIDTH).fillMaxWidth()

/** The widest a short form grows (sign-in, a new band, the screens with nothing but a few fields and a button). */
val MAX_FORM_WIDTH = 420.dp

/** Caps a form at [MAX_FORM_WIDTH]. */
fun Modifier.formWidth(): Modifier = widthIn(max = MAX_FORM_WIDTH)
