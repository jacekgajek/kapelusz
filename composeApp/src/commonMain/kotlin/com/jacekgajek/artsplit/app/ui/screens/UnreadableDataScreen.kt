package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.start_over
import com.jacekgajek.artsplit.app.generated.unreadable_text
import com.jacekgajek.artsplit.app.generated.unreadable_title
import org.jetbrains.compose.resources.stringResource

/** Shown when saved data cannot be read. The data was already backed up, so starting over loses nothing. */
@Composable
fun UnreadableDataScreen(onStartOver: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.unreadable_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.unreadable_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.start_over)) }
        }
    }
}
