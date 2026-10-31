package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.other_tab_text
import com.jacekgajek.kapelusz.app.generated.other_tab_title
import com.jacekgajek.kapelusz.app.generated.use_here
import com.jacekgajek.kapelusz.app.ui.components.formWidth
import org.jetbrains.compose.resources.stringResource

/** Web: the app is already open in another tab, which owns the stored data. */
@Composable
fun OtherTabScreen(onUseHere: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.formWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.other_tab_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.other_tab_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onUseHere, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.use_here)) }
        }
    }
}
